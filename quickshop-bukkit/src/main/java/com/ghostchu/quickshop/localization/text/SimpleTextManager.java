package com.ghostchu.quickshop.localization.text;

import com.ghostchu.crowdin.CrowdinOTA;
import com.ghostchu.crowdin.OTAFileInstance;
import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.localization.text.postprocessor.PostProcessor;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.localization.text.postprocessing.impl.FillerProcessor;
import com.ghostchu.quickshop.localization.text.postprocessing.impl.PlaceHolderApiProcessor;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.ReloadStatus;
import com.ghostchu.simplereloadlib.Reloadable;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import kong.unirest.Unirest;
import lombok.SneakyThrows;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class SimpleTextManager implements TextManager, Reloadable {
    private static final String DEFAULT_LOCALE = "en_us";
    private static final String LOCALE_MAPPING_SYNTAX = "locale";
    private static final String CROWDIN_LANGUAGE_FILE_PATH = "/hikari/crowdin/lang/%locale%/messages.yml";
    public final Set<PostProcessor> postProcessors = new LinkedHashSet<>();
    private final QuickShop plugin;
    // <File <Locale, Section>>
    private final LanguageFilesManager languageFilesManager = new LanguageFilesManager();
    private final Set<String> availableLanguages = new LinkedHashSet<>();
    private final Cache<String, String> languagesCache =
            CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).build();
    @Nullable
    private CrowdinOTA crowdinOTA;

    public SimpleTextManager(@NotNull QuickShop plugin) {
        this.plugin = plugin;
        plugin.getReloadManager().register(this);
        if (!Util.parsePackageProperly("enableCrowdinOTA").isPresent() || Util.parsePackageProperly("enableCrowdinOTA").asBoolean()) {
            try {
                plugin.getLogger().info("Please wait us fetch the translation updates from Crowdin OTA service...");
                this.crowdinOTA = new CrowdinOTA(Util.parsePackageProperly("crowdinHost").asString("https://crowdinota.hikari.r2.quickshop-powered.top"), new File(Util.getCacheFolder(), "crowdin-ota"), Unirest.primaryInstance());
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Cannot initialize the CrowdinOTA instance!", e);
            }
        } else {
            plugin.getLogger().info("[CrowdinOTA] Crowdin Over-The-Air distribution has been disabled.");
        }
        load();
    }

    @Override
    public ReloadResult reloadModule() {
        Util.asyncThreadRun(this::load);
        return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
    }

    @NotNull
    private FileConfiguration loadBuiltInFallback() {
        YamlConfiguration configuration = new YamlConfiguration();
        try (InputStream inputStream = QuickShop.getInstance().getResource("lang/messages.yml")) {
            if (inputStream == null) {
                plugin.getLogger().log(Level.WARNING, "Failed to load built-in fallback translation, fallback file not exists in jar.");
                return configuration;
            }
            byte[] bytes = inputStream.readAllBytes();
            String content = new String(bytes, StandardCharsets.UTF_8);
            configuration.loadFromString(content);
            return configuration;
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load built-in fallback translation.", e);
            return configuration;
        }
    }

    /**
     * Loading Crowdin OTA module and i18n system
     */
    public void load() {
        plugin.getLogger().info("Loading up translations from Crowdin OTA, this may need a while...");
        //TODO: This will break the message processing system in-game until loading finished, need to fix it.
        this.reset();
        // first, we need load built-in fallback translation.
        languageFilesManager.deploy("en_us", loadBuiltInFallback());
        // second, load the bundled language files
        loadBundled().forEach(languageFilesManager::deploy);
        // then, load the translations from Crowdin
        try {
            if (crowdinOTA != null) {
                OTAFileInstance fileInstance = crowdinOTA.getOtaInstance().getFileInstance(CROWDIN_LANGUAGE_FILE_PATH);
                if (fileInstance != null) {
                    for (String crowdinCode : fileInstance.getAvailableLocales()) {
                        String content = fileInstance.getLocaleContentByCrowdinCode(crowdinCode);
                        String mcCode = crowdinOTA.mapLanguageCode(crowdinCode, LOCALE_MAPPING_SYNTAX).toLowerCase(Locale.ROOT).replace("-", "_");
                        if (content == null) {
                            plugin.getLogger().log(Level.WARNING, "Failed to load translation for " + mcCode + ", the content is null.");
                            continue;
                        }
                        YamlConfiguration configuration = new YamlConfiguration();
                        try {
                            configuration.loadFromString(content);
                            languageFilesManager.deploy(mcCode, configuration);
                        } catch (InvalidConfigurationException e) {
                            plugin.getLogger().log(Level.WARNING, "Failed to load translation for " + mcCode + ".", e);
                        }
                    }
                }
            } else {
                plugin.getLogger().info("CrowdinOTA not initialized, skipping for over-the-air translation updates.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Unable to load Crowdin OTA translations", e);
        }
        // and don't forget fix missing
        languageFilesManager.fillMissing(loadBuiltInFallback());
        // finally, load override translations
        Collection<String> pending = getOverrideLocales(languageFilesManager.getDistributions().keySet());
        Log.debug("Pending: " + Arrays.toString(pending.toArray()));
        pending.forEach(locale -> {
            File file = getOverrideLocaleFile(locale);
            if (file.exists()) {
                YamlConfiguration configuration = new YamlConfiguration();
                try {
                    configuration.loadFromString(Files.readString(file.toPath(), StandardCharsets.UTF_8));
                    languageFilesManager.deploy(locale, configuration);
                    Log.debug("Override file loaded for " + locale + " with file " + file.getAbsolutePath());
                } catch (InvalidConfigurationException | IOException e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to override translation for " + locale + ".", e);
                }

            } else {
                Log.debug("Override not applied: File " + file.getAbsolutePath() + " not exists.");
            }
        });

        // Remove disabled locales
        List<String> enabledLanguagesRegex = plugin.getConfig().getStringList("enabled-languages");
        enabledLanguagesRegex.replaceAll(s -> s.toLowerCase(Locale.ROOT).replace("-", "_"));
        Iterator<String> it = pending.iterator();
        while (it.hasNext()) {
            String locale = it.next();
            if (!localeEnabled(locale, enabledLanguagesRegex)) {
                this.languageFilesManager.destroy(locale);
                it.remove();
            }
        }
        // Remember all available languages
        availableLanguages.addAll(pending);

        // Register post processor
        postProcessors.add(new FillerProcessor());
        postProcessors.add(new PlaceHolderApiProcessor());

    }

    /**
     * Reset everything
     */
    private void reset() {
        languagesCache.cleanUp();
        languageFilesManager.reset();
        postProcessors.clear();
        availableLanguages.clear();
    }

    /**
     * Loading translations from bundled resources
     *
     * @return The bundled translations, empty hash map if nothing can be load.
     */
    @NotNull
    private Map<String, FileConfiguration> loadBundled() {
        File jarFile;
        try {
            jarFile = Util.getPluginJarFile(plugin);
        } catch (FileNotFoundException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load bundled translation: " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>();
        }
        try (ZipFile zipFile = new ZipFile(jarFile, "UTF-8")) {
            // jar/lang/<region_code>/
            Map<String, FileConfiguration> availableLang = new HashMap<>();
            zipFile.getEntries().asIterator().forEachRemaining(entry -> {
                if (entry.isDirectory()) {
                    return;
                }
                if (!entry.getName().startsWith("lang/")) {
                    return;
                }
                if (!entry.getName().endsWith("messages.yml")) {
                    return;
                }
                String[] split = entry.getName().split("/");
                String locale = split[split.length - 2];
                if (zipFile.canReadEntryData(entry)) {
                    try {
                        YamlConfiguration configuration = new YamlConfiguration();
                        configuration.loadFromString(new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8));
                        availableLang.put(locale.toLowerCase(Locale.ROOT).replace("-", "_"), configuration);
                        Log.debug("Bundled language file: " + locale + " at " + entry.getName() + " loaded.");
                    } catch (IOException | InvalidConfigurationException e) {
                        plugin.getLogger().log(Level.WARNING, "Failed to load bundled translation.", e);
                    }
                }
            });
            return availableLang;
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load bundled translation, jar invalid: " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>();
        }

    }

    /**
     * Gets specific locale status
     *
     * @param locale The locale
     * @param regex  The regexes
     * @return The locale enabled status
     */
    @Override
    public boolean localeEnabled(@NotNull String locale, @NotNull List<String> regex) {
        for (String languagesRegex : regex) {
            try {
                if (Pattern.matches(CommonUtil.createRegexFromGlob(languagesRegex), locale)) {
                    return true;
                }
            } catch (PatternSyntaxException exception) {
                Log.debug("Pattern " + languagesRegex + " invalid, skipping...");
            }
        }
        return false;
    }

    @NotNull
    private File getOverrideLocaleFile(@NotNull String locale) {
        File file;
        // bug fixes workaround
        file = new File(new File(plugin.getDataFolder(), "overrides"), locale + ".yml");
        if (file.isDirectory()) { // Fix bad directory name.
            file.delete();
        }
        file = new File(new File(plugin.getDataFolder(), "overrides"), locale);
        file = new File(file, "messages.yml");
        return file;
    }

    /**
     * Generate the override files storage path
     *
     * @param pool The language codes you already own.
     * @return The pool copy with new added language codes.
     */
    @SneakyThrows(IOException.class)
    @NotNull
    private Collection<String> getOverrideLocales(@NotNull Collection<String> pool) {
        File moduleFolder = new File(plugin.getDataFolder(), "overrides");
        if (!moduleFolder.exists()) {
            moduleFolder.mkdirs();
        }
        // create the pool overrides placeholder directories
        pool.forEach(single -> {
            File f = new File(moduleFolder, single);
            if (!f.exists()) {
                f.mkdirs();
            }
        });
        //
        File[] files = moduleFolder.listFiles();
        if (files == null) {
            return pool;
        }
        List<String> newPool = new ArrayList<>(pool);
        for (File file : files) {
            if (file.isDirectory()) {
                // custom language
                newPool.add(file.getName());
                // create the paired file
                File localeFile = new File(file, "messages.yml");
                if (!localeFile.exists()) {
                    localeFile.getParentFile().mkdirs();
                    localeFile.createNewFile();
                } else {
                    if (localeFile.isDirectory()) {
                        localeFile.delete();
                    }
                }
            }
        }
        return newPool;
    }

    @Override
    @NotNull
    public ProxiedLocale findRelativeLanguages(@Nullable String langCode) {
        //langCode may null when some plugins providing fake player
        if (langCode == null || langCode.isEmpty()) {
            return new ProxiedLocale(langCode, DEFAULT_LOCALE);
        }
        String result = languagesCache.getIfPresent(langCode);
        if (result == null) {
            result = DEFAULT_LOCALE;
            if (availableLanguages.contains(langCode)) {
                result = langCode;
            } else {
                String[] splits = langCode.split("_", 2);
                String start = langCode;
                String end = langCode;
                if (splits.length == 2) {
                    start = splits[0] + "_";
                    end = "_" + splits[1];
                }
                for (String availableLanguage : availableLanguages) {
                    if (availableLanguage.startsWith(start) || availableLanguage.endsWith(end)) {
                        result = availableLanguage;
                        break;
                    }
                }
            }
            Log.debug("Registering relative language " + langCode + " to " + result);
            languagesCache.put(langCode, result);
        }
        return new ProxiedLocale(langCode, result);
    }

    @Override
    public @NotNull ProxiedLocale findRelativeLanguages(@Nullable CommandSender sender) {
        if (sender instanceof Player player) {
            return findRelativeLanguages(player.getLocale());
        }
        return findRelativeLanguages(MsgUtil.getDefaultGameLanguageCode());
    }

    /**
     * Register the language phrase to QuickShop text manager in runtime.
     *
     * @param locale Target locale
     * @param path   The language key path
     * @param text   The language text
     */
    @SneakyThrows(InvalidConfigurationException.class)
    @Override
    public void register(@NotNull String locale, @NotNull String path, @NotNull String text) {
        FileConfiguration configuration = languageFilesManager.getDistribution(locale);
        if (configuration == null) {
            configuration = new YamlConfiguration();
            configuration.loadFromString(languageFilesManager.getDistribution(DEFAULT_LOCALE).saveToString());
        }
        configuration.set(path, text);
        languageFilesManager.deploy(locale, configuration);
    }

    @Override
    public @NotNull ProxiedLocale findRelativeLanguages(@Nullable UUID sender) {
        if (sender == null) {
            return findRelativeLanguages(MsgUtil.getDefaultGameLanguageCode());
        }
        Player player = Bukkit.getPlayer(sender);
        if (player != null) {
            return findRelativeLanguages(player);
        }
        return findRelativeLanguages(MsgUtil.getDefaultGameLanguageCode());

    }

    /**
     * Getting the translation with path with default locale
     *
     * @param path THe path
     * @param args The arguments
     * @return The text object
     */
    @Override
    public @NotNull Text of(@NotNull String path, Object... args) {
        return new Text(this, (CommandSender) null, languageFilesManager.getDistributions(), path, convert(args));
    }

    /**
     * Getting the translation with path with player's locale (if available)
     *
     * @param sender The sender
     * @param path   The path
     * @param args   The arguments
     * @return The text object
     */
    @Override
    public @NotNull Text of(@Nullable CommandSender sender, @NotNull String path, Object... args) {
        return new Text(this, sender, languageFilesManager.getDistributions(), path, convert(args));
    }

    /**
     * Getting the translation with path with player's locale (if available)
     *
     * @param sender The player unique id
     * @param path   The path
     * @param args   The arguments
     * @return The text object
     */
    @Override
    public @NotNull Text of(@Nullable UUID sender, @NotNull String path, Object... args) {
        return new Text(this, sender, languageFilesManager.getDistributions(), path, convert(args));
    }

    @Override
    @NotNull
    public Component[] convert(@Nullable Object... args) {
        if (args == null || args.length == 0) {
            return new Component[0];
        }
        Component[] components = new Component[args.length];
        for (int i = 0; i < args.length; i++) {
            Object obj = args[i];
            if (obj == null) {
                components[i] = Component.empty();
                continue;
            }
            Class<?> clazz = obj.getClass();
            if (obj instanceof Component component) {
                components[i] = component;
                continue;
            }
            if (obj instanceof ComponentLike componentLike) {
                components[i] = componentLike.asComponent();
                continue;
            }
            // Check
            try {
                if (Character.class.equals(clazz)) {
                    components[i] = Component.text((char) obj);
                    continue;
                }
                if (Byte.class.equals(clazz)) {
                    components[i] = Component.text((Byte) obj);
                    continue;
                }
                if (Integer.class.equals(clazz)) {
                    components[i] = Component.text((Integer) obj);
                    continue;
                }
                if (Long.class.equals(clazz)) {
                    components[i] = Component.text((Long) obj);
                    continue;
                }
                if (Float.class.equals(clazz)) {
                    components[i] = Component.text((Float) obj);
                    continue;
                }
                if (Double.class.equals(clazz)) {
                    components[i] = Component.text((Double) obj);
                    continue;
                }
                if (Boolean.class.equals(clazz)) {
                    components[i] = Component.text((Boolean) obj);
                    continue;
                }
                if (String.class.equals(clazz)) {
                    components[i] = LegacyComponentSerializer.legacySection().deserialize((String) obj);
                    continue;
                }
                components[i] = LegacyComponentSerializer.legacySection().deserialize(obj.toString());
            } catch (Exception exception) {
                Log.debug("Failed to process the object: " + obj);
                if (plugin.getSentryErrorReporter() != null) {
                    plugin.getSentryErrorReporter().sendError(exception, "Failed to process the object: " + obj);
                }
                components[i] = LegacyComponentSerializer.legacySection().deserialize(obj.toString());
            }
            // undefined

        }
        return components;
    }

    /**
     * Getting the translation with path with default locale (if available)
     *
     * @param path The path
     * @param args The arguments
     * @return The text object
     */
    @Override
    public @NotNull TextList ofList(@NotNull String path, Object... args) {
        return new TextList(this, (CommandSender) null, languageFilesManager.getDistributions(), path, convert(args));
    }

    /**
     * Return the set of available Languages
     *
     * @return the set of available Languages
     */
    @Override
    public List<String> getAvailableLanguages() {
        return new ArrayList<>(availableLanguages);
    }

    /**
     * Getting the translation with path  with player's locale (if available)
     *
     * @param sender The player unique id
     * @param path   The path
     * @param args   The arguments
     * @return The text object
     */
    @Override
    public @NotNull TextList ofList(@Nullable UUID sender, @NotNull String path, Object... args) {
        return new TextList(this, sender, languageFilesManager.getDistributions(), path, convert(args));
    }

    /**
     * Getting the translation with path with player's locale (if available)
     *
     * @param sender The player
     * @param path   The path
     * @param args   The arguments
     * @return The text object
     */
    @Override
    public @NotNull TextList ofList(@Nullable CommandSender sender, @NotNull String path, Object... args) {
        return new TextList(this, sender, languageFilesManager.getDistributions(), path, convert(args));
    }

    public static class TextList implements com.ghostchu.quickshop.api.localization.text.TextList {
        private final SimpleTextManager manager;
        private final String path;
        private final Map<String, FileConfiguration> mapping;
        private final CommandSender sender;
        private final Component[] args;

        private TextList(SimpleTextManager manager, CommandSender sender, Map<String, FileConfiguration> mapping, String path, Component... args) {
            this.manager = manager;
            this.sender = sender;
            this.mapping = mapping;
            this.path = path;
            this.args = args;
        }

        private TextList(SimpleTextManager manager, UUID sender, Map<String, FileConfiguration> mapping, String path, Component... args) {
            this.manager = manager;
            if (sender != null) {
                this.sender = Bukkit.getPlayer(sender);
            } else {
                this.sender = null;
            }
            this.mapping = mapping;
            this.path = path;
            this.args = args;
        }

        /**
         * Getting the text that use specify locale
         *
         * @param locale The minecraft locale code (like en_us)
         * @return The text
         */
        @Override
        @NotNull
        public List<Component> forLocale(@NotNull String locale) {
            FileConfiguration index = mapping.get(manager.findRelativeLanguages(locale).getLocale());
            if (index == null) {
                Log.debug("Fallback " + locale + " to default game-language locale caused by QuickShop doesn't support this locale");
                String languageCode = MsgUtil.getDefaultGameLanguageCode();
                if (languageCode.equals(locale)) {
                    Log.debug("Fallback Missing Language Key: " + path + ", report to QuickShop!");
                    return Collections.singletonList(LegacyComponentSerializer.legacySection().deserialize(path));
                } else {
                    return forLocale(languageCode);
                }
            } else {
                List<String> str = index.getStringList(path);
                if (str.isEmpty()) {
                    Log.debug("Fallback Missing Language Key: " + path + ", report to QuickShop!");
                    return Collections.singletonList(LegacyComponentSerializer.legacySection().deserialize(path));
                }
                List<Component> components = str.stream().map(s -> manager.plugin.getPlatform().miniMessage().deserialize(s)).toList();
                return postProcess(components);
            }
        }

        /**
         * Getting the text for player locale
         *
         * @return Getting the text for player locale
         */
        @Override
        @NotNull
        public List<Component> forLocale() {
            if (sender instanceof Player player) {
                return forLocale(player.getLocale());
            } else {
                return forLocale(MsgUtil.getDefaultGameLanguageCode());
            }
        }

        /**
         * Getting this text is exists in the translation file
         *
         * @return true if this text is exists in the translation file
         */
        @Override
        public boolean isPresent() {
            String locale;
            if (sender instanceof Player player) {
                locale = player.getLocale();
            } else {
                locale = MsgUtil.getDefaultGameLanguageCode();
            }
            FileConfiguration index = mapping.get(manager.findRelativeLanguages(locale).getLocale());
            return index != null;
        }

        /**
         * Send text to the player
         */
        @Override
        public void send() {
            if (sender == null) {
                throw new IllegalStateException("Sender is null");
            }
            for (Component s : forLocale()) {
                MsgUtil.sendDirectMessage(sender, s);
            }
        }

        /**
         * Post processes the text
         *
         * @param text The text
         * @return The text that processed
         */
        @NotNull
        private List<Component> postProcess(@NotNull List<Component> text) {
            List<Component> texts = new ArrayList<>();
            for (PostProcessor postProcessor : this.manager.postProcessors) {
                for (Component s : text) {
                    texts.add(postProcessor.process(s, sender, args));
                }
            }
            return texts;
        }
    }

    public static class Text implements com.ghostchu.quickshop.api.localization.text.Text {
        private final SimpleTextManager manager;
        private final String path;
        private final Map<String, FileConfiguration> mapping;
        private final CommandSender sender;
        private final Component[] args;

        private Text(SimpleTextManager manager, CommandSender sender, Map<String, FileConfiguration> mapping, String path, Component... args) {
            this.manager = manager;
            this.sender = sender;
            this.mapping = mapping;
            this.path = path;
            this.args = args;
        }

        private Text(SimpleTextManager manager, UUID sender, Map<String, FileConfiguration> mapping, String path, Component... args) {
            this.manager = manager;
            if (sender != null) {
                this.sender = Bukkit.getPlayer(sender);
            } else {
                this.sender = null;
            }
            this.mapping = mapping;
            this.path = path;
            this.args = args;
        }

        /**
         * Getting the text that use specify locale
         *
         * @param locale The minecraft locale code (like en_us)
         * @return The text
         */
        @Override
        @NotNull
        public Component forLocale(@NotNull String locale) {
            FileConfiguration index = mapping.get(manager.findRelativeLanguages(locale).getLocale());
            if (index == null) {
                Log.debug("Index for " + locale + " is null");
                Log.debug("Fallback " + locale + " to default game-language locale caused by QuickShop doesn't support this locale");
                if (MsgUtil.getDefaultGameLanguageCode().equals(locale)) {
                    Log.debug("Fallback Missing Language Key: " + path + ", report to QuickShop!");
                    return LegacyComponentSerializer.legacySection().deserialize(path);
                } else {
                    return forLocale(MsgUtil.getDefaultGameLanguageCode());
                }
            } else {
                String str = index.getString(path);
                if (str == null) {
                    Log.debug("The value about index " + index + " is null");
                    Log.debug("Missing Language Key: " + path + ", report to QuickShop!");
                    StringJoiner joiner = new StringJoiner(".");
                    String[] pathExploded = path.split("\\.");
                    for (String singlePath : pathExploded) {
                        joiner.add(singlePath);
                        Log.debug("Path debug: " + joiner + " is " + index.get(singlePath, "null"));
                    }
                    return LegacyComponentSerializer.legacySection().deserialize(path);
                }
                Component component = manager.plugin.getPlatform().miniMessage().deserialize(str);
                return postProcess(component);
            }
        }

        /**
         * Getting the text for player locale
         *
         * @return Getting the text for player locale
         */
        @Override
        @NotNull
        public Component forLocale() {
            if (sender instanceof Player player) {
                return forLocale(player.getLocale());
            } else {
                return forLocale(MsgUtil.getDefaultGameLanguageCode());
            }
        }

        /**
         * Getting this text is exists in the translation file
         *
         * @return true if this text is exists in the translation file
         */
        @Override
        public boolean isPresent() {
            String locale;
            if (sender instanceof Player player) {
                locale = player.getLocale();
            } else {
                locale = MsgUtil.getDefaultGameLanguageCode();
            }
            FileConfiguration index = mapping.get(manager.findRelativeLanguages(locale).getLocale());
            return index != null;
        }

        /**
         * Send text to the player
         */
        @Override
        public void send() {
            if (sender == null) {
                throw new IllegalStateException("Sender is null");
            }
            Component lang = forLocale();
            MsgUtil.sendDirectMessage(sender, lang);
        }

        /**
         * Post processes the text
         *
         * @param text The text
         * @return The text that processed
         */
        @NotNull
        private Component postProcess(@NotNull Component text) {
            for (PostProcessor postProcessor : this.manager.postProcessors) {
                text = postProcessor.process(text, sender, args);
            }
            return text;
        }
    }


}
