package com.ghostchu.quickshop;

import cc.carm.lib.easysql.EasySQL;
import cc.carm.lib.easysql.api.SQLManager;
import cc.carm.lib.easysql.hikari.HikariConfig;
import cc.carm.lib.easysql.hikari.HikariDataSource;
import cc.carm.lib.easysql.manager.SQLManagerImpl;
import com.comphenix.protocol.ProtocolLibrary;
import com.ghostchu.quickshop.api.GameVersion;
import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.QuickShopProvider;
import com.ghostchu.quickshop.api.command.CommandManager;
import com.ghostchu.quickshop.api.database.DatabaseHelper;
import com.ghostchu.quickshop.api.economy.AbstractEconomy;
import com.ghostchu.quickshop.api.economy.EconomyType;
import com.ghostchu.quickshop.api.event.QSConfigurationReloadEvent;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperManager;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperRegistry;
import com.ghostchu.quickshop.api.localization.text.TextManager;
import com.ghostchu.quickshop.api.shop.ItemMatcher;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopControlPanelManager;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.api.shop.display.DisplayType;
import com.ghostchu.quickshop.command.QuickShopCommand;
import com.ghostchu.quickshop.command.SimpleCommandManager;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.common.util.Timer;
import com.ghostchu.quickshop.database.HikariUtil;
import com.ghostchu.quickshop.database.SimpleDatabaseHelperV2;
import com.ghostchu.quickshop.economy.Economy_GemsEconomy;
import com.ghostchu.quickshop.economy.Economy_TNE;
import com.ghostchu.quickshop.economy.Economy_Vault;
import com.ghostchu.quickshop.listener.*;
import com.ghostchu.quickshop.localization.text.SimpleTextManager;
import com.ghostchu.quickshop.metric.MetricListener;
import com.ghostchu.quickshop.papi.QuickShopPAPI;
import com.ghostchu.quickshop.permission.PermissionManager;
import com.ghostchu.quickshop.platform.Platform;
import com.ghostchu.quickshop.platform.paper.PaperPlatform;
import com.ghostchu.quickshop.platform.spigot.AbstractSpigotPlatform;
import com.ghostchu.quickshop.platform.spigot.v1_18_1.Spigot1181Platform;
import com.ghostchu.quickshop.platform.spigot.v1_18_2.Spigot1182Platform;
import com.ghostchu.quickshop.platform.spigot.v1_19_1.Spigot1191Platform;
import com.ghostchu.quickshop.platform.spigot.v1_19_2.Spigot1193Platform;
import com.ghostchu.quickshop.shop.*;
import com.ghostchu.quickshop.shop.controlpanel.SimpleShopControlPanel;
import com.ghostchu.quickshop.shop.controlpanel.SimpleShopControlPanelManager;
import com.ghostchu.quickshop.shop.display.AbstractDisplayItem;
import com.ghostchu.quickshop.shop.display.VirtualDisplayItem;
import com.ghostchu.quickshop.shop.inventory.BukkitInventoryWrapperManager;
import com.ghostchu.quickshop.shop.signhooker.SignHooker;
import com.ghostchu.quickshop.util.*;
import com.ghostchu.quickshop.util.config.ConfigUpdateScript;
import com.ghostchu.quickshop.util.config.ConfigurationUpdater;
import com.ghostchu.quickshop.util.envcheck.*;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.quickshop.util.matcher.item.BukkitItemMatcherImpl;
import com.ghostchu.quickshop.util.matcher.item.QuickShopItemMatcherImpl;
import com.ghostchu.quickshop.util.performance.PerfMonitor;
import com.ghostchu.quickshop.util.reporter.error.RollbarErrorReporter;
import com.ghostchu.quickshop.util.updater.NexusManager;
import com.ghostchu.quickshop.watcher.*;
import com.ghostchu.simplereloadlib.ReloadManager;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.Reloadable;
import io.papermc.lib.PaperLib;
import kong.unirest.Unirest;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.Adventure;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.h2.Driver;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Level;

public class QuickShop extends JavaPlugin implements QuickShopAPI, Reloadable {
    /**
     * If running environment test
     */
    @Getter
    private static final boolean TESTING = false;
    /**
     * The active instance of QuickShop
     * You shouldn't use this if you really need it.
     */
    @ApiStatus.Internal
    private static QuickShop instance;
    /**
     * The manager to check permissions.
     */
    private static PermissionManager permissionManager;
    private final Map<String, Integer> limits = new HashMap<>(15);
    @Getter
    private final ReloadManager reloadManager = new ReloadManager();
    private final InventoryWrapperRegistry inventoryWrapperRegistry = new InventoryWrapperRegistry();
    @Getter
    private final InventoryWrapperManager inventoryWrapperManager = new BukkitInventoryWrapperManager();
    @Getter
    private final ShopControlPanelManager shopControlPanelManager = new SimpleShopControlPanelManager(this);
    private final Map<String, String> addonRegisteredMapping = new HashMap<>();
    /* Public QuickShop API End */
    boolean onLoadCalled = false;
    private GameVersion gameVersion;
    private SimpleDatabaseHelperV2 databaseHelper;
    private SimpleCommandManager commandManager;
    private ItemMatcher itemMatcher;
    private SimpleShopManager shopManager;
    private SimpleTextManager textManager;
    @Getter
    private SimpleShopPermissionManager shopPermissionManager;
    @Getter
    private final ShopBackupUtil shopBackupUtil = new ShopBackupUtil(this);
    private boolean priceChangeRequiresFee = false;
    @Getter
    private DatabaseDriverType databaseDriverType = null;
    /**
     * The BootError, if it not NULL, plugin will stop loading and show setted errors when use /qs
     */
    @Nullable
    @Getter
    @Setter
    private BootError bootError;
    /**
     * Default database prefix, can overwrite by config
     */
    @Getter
    private String dbPrefix = "";
    /**
     * Whether we should use display items or not
     */
    private boolean display = true;
    @Getter
    private int displayItemCheckTicks;
    /**
     * The economy we hook into for transactions
     */
    @Getter
    private AbstractEconomy economy;
    /**
     * Whether or not to limit players shop amounts
     */
    private boolean limit = false;
    @Nullable
    @Getter
    private LogWatcher logWatcher;
    /**
     * bStats, good helper for metrics.
     */
    private Metrics metrics;
    /**
     * The plugin PlaceHolderAPI(null if not present)
     */
    @Getter
    private Plugin placeHolderAPI;
    /**
     * A util to call to check some actions permission
     */
    @Getter
    private PermissionChecker permissionChecker;
    /**
     * The error reporter to help devs report errors to Sentry.io
     */
    @Getter
    @Nullable
    private RollbarErrorReporter sentryErrorReporter;
    /**
     * The server UniqueID, use to the ErrorReporter
     */
    @Getter
    private UUID serverUniqueID;
    private boolean setupDBonEnableding = false;
    /**
     * Rewrited shoploader, more faster.
     */
    @Getter
    private ShopLoader shopLoader;
    @Getter
    private DisplayAutoDespawnWatcher displayAutoDespawnWatcher;
    @Getter
    private OngoingFeeWatcher ongoingFeeWatcher;
    @Getter
    private SignUpdateWatcher signUpdateWatcher;
    //    @Getter
//    private ShopContainerWatcher shopContainerWatcher;
    @Getter
    private Cache shopCache;
    @Getter
    private boolean allowStack;
    @Getter
    private EnvironmentChecker environmentChecker;
    @Getter
    @Nullable
    private UpdateWatcher updateWatcher;
    @Getter
    private BuildInfo buildInfo;
    @Getter
    @Nullable
    private String currency = null;
    @Getter
    private CalendarWatcher calendarWatcher;
    @Getter
    private ShopPurger shopPurger;
    private int loggingLocation = 0;
    @Getter
    private InteractionController interactionController;
    @Getter
    private SQLManager sqlManager;
    @Getter
    @Nullable
    private QuickShopPAPI quickShopPAPI;
    @Getter
    private Platform platform;
    private BukkitAudiences audience;
    @Getter
    private ItemMarker itemMarker;
    private Map<String, String> translationMapping;
    @Getter
    private FastPlayerFinder playerFinder;
    @Getter
    private ShopItemBlackList shopItemBlackList;
    @Getter
    private NexusManager nexusManager;
    @Getter
    private ShopDataSaveWatcher shopSaveWatcher;
    @Getter
    private SignHooker signHooker;
    @Getter
    private BungeeListener bungeeListener;

    /**
     * Use for mock bukkit
     */
    public QuickShop() {
        super();
    }

    /**
     * Use for mock bukkit
     */
    protected QuickShop(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
        super(loader, description, dataFolder, file);
    }

    /**
     * Return the QuickShop fork name.
     *
     * @return The fork name.
     */
    public static String getFork() {
        return "Hikari";
    }

    /**
     * Returns QS version, this method only exist on QuickShop forks If running other QuickShop forks, result
     * may not is "Reremake x.x.x" If running QS official, Will throw exception.
     *
     * @return Plugin Version
     */
    @NotNull
    public static String getVersion() {
        return QuickShop.getInstance().getDescription().getVersion();
    }

    /**
     * Get the QuickShop instance
     * You should use QuickShopAPI if possible, we don't promise the internal access will be stable
     *
     * @return QuickShop instance
     * @apiNote This method is internal only.
     * @hidden This method is hidden in documentation.
     */
    @ApiStatus.Internal
    @NotNull
    public static QuickShop getInstance() {
        return instance;
    }

    private void bakeShopsOwnerCache() {
        if (Util.parsePackageProperly("bakeuuids").asBoolean()) {
            getLogger().info("Baking shops owner and moderators caches (This may take a while if you upgrade from old versions)...");
            Set<UUID> waitingForBake = new HashSet<>();
            this.shopManager.getAllShops().forEach(shop -> {
                if (!this.playerFinder.isCached(shop.getOwner())) {
                    waitingForBake.add(shop.getOwner());
                }
                shop.getPermissionAudiences().keySet().forEach(audience -> {
                    if (!this.playerFinder.isCached(audience)) {
                        waitingForBake.add(audience);
                    }
                });
            });

            if (waitingForBake.isEmpty()) {
                return;
            }
            getLogger().info("Resolving " + waitingForBake.size() + " player UUID and Name mappings...");
            waitingForBake.forEach(uuid -> {
                String name = playerFinder.uuid2Name(uuid);
                if (name == null) {
                    getLogger().info("Invalid Player lookup, skip.");
                    return;
                }
                getLogger().info("Resolved: " + uuid + "( " + name + " ), " + (waitingForBake.size() - 1) + " jobs remains.");
            });
        }
    }

    @NotNull
    @Deprecated(forRemoval = true)
    public BukkitAudiences getAudience() {
        return this.audience;
    }

    @Override
    public CommandManager getCommandManager() {
        return this.commandManager;
    }

    @Override
    public DatabaseHelper getDatabaseHelper() {
        return this.databaseHelper;
    }

    @Override
    public GameVersion getGameVersion() {
        if (gameVersion == null) {
            gameVersion = GameVersion.get(ReflectFactory.getNMSVersion());
        }
        return this.gameVersion;
    }

    @Override
    public @NotNull InventoryWrapperRegistry getInventoryWrapperRegistry() {
        return inventoryWrapperRegistry;
    }

    @Override
    public ItemMatcher getItemMatcher() {
        return this.itemMatcher;
    }

    @Override
    public Map<String, Integer> getLimits() {
        return this.limits;
    }

    @Override
    public ShopManager getShopManager() {
        return this.shopManager;
    }

    @Override
    public TextManager getTextManager() {
        return this.textManager;
    }

    @Override
    public boolean isDisplayEnabled() {
        return this.display;
    }

    @Override
    public boolean isLimit() {
        return this.limit;
    }

    @Override
    public boolean isPriceChangeRequiresFee() {
        return this.priceChangeRequiresFee;
    }

    @Override
    public void logEvent(@NotNull Object eventObject) {
        if (this.getLogWatcher() == null) {
            return;
        }
        if (loggingLocation == 0) {
            this.getLogWatcher().log(JsonUtil.getGson().toJson(eventObject));
        } else {
            getDatabaseHelper().insertHistoryRecord(eventObject)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null) {
                            Log.debug("Failed to log event: " + throwable.getMessage());
                        }
                    });
        }

    }

    @Override
    public void registerLocalizedTranslationKeyMapping(@NotNull String translationKey, @NotNull String key) {
        addonRegisteredMapping.put(translationKey, key);
        translationMapping.putAll(addonRegisteredMapping);
        if (this.platform != null) {
            this.platform.updateTranslationMappingSection(translationMapping);
        }
    }

    /**
     * Get the Player's Shop limit.
     *
     * @param p The player you want get limit.
     * @return int Player's shop limit
     */
    public int getShopLimit(@NotNull Player p) {
        int max = getConfig().getInt("limits.default");
        for (Entry<String, Integer> entry : limits.entrySet()) {
            if (entry.getValue() > max && getPermissionManager().hasPermission(p, entry.getKey())) {
                max = entry.getValue();
            }
        }
        return max;
    }

    /**
     * Get the permissionManager as static
     *
     * @return the permission Manager.
     */
    @NotNull
    public static PermissionManager getPermissionManager() {
        return permissionManager;
    }

    private void initConfiguration() {
        /* Process the config */
        //noinspection ResultOfMethodCallIgnored
        getDataFolder().mkdirs();
        try {
            saveDefaultConfig();
        } catch (IllegalArgumentException resourceNotFoundException) {
            getLogger().severe("Failed to save config.yml from jar, The binary file of QuickShop may be corrupted. Please re-download from our website.");
        }
        reloadConfig();
        if (getConfig().getInt("config-version", 0) == 0) {
            getConfig().set("config-version", 1);
        }
        /* It will generate a new UUID above updateConfig */
        this.serverUniqueID = UUID.fromString(Objects.requireNonNull(getConfig().getString("server-uuid", String.valueOf(UUID.randomUUID()))));
        updateConfig();
    }

    private void initDatabase() {
        setupDBonEnableding = true;
        if (!setupDatabase()) {
            getLogger().severe("Failed to setup database, please check the logs for more information!");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        setupDBonEnableding = false;
    }

    /**
     * Load 3rdParty plugin support module.
     */
    private void load3rdParty() {
        if (getConfig().getBoolean("plugin.PlaceHolderAPI")) {
            this.placeHolderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (this.placeHolderAPI != null && placeHolderAPI.isEnabled()) {
                this.quickShopPAPI = new QuickShopPAPI();
                this.quickShopPAPI.register();
                getLogger().info("Successfully loaded PlaceHolderAPI support!");
            }
        }

        if (this.display) {
            //VirtualItem support
            if (AbstractDisplayItem.getNowUsing() == DisplayType.VIRTUALITEM) {
                getLogger().info("Using Virtual Item display, loading ProtocolLib support...");
                Plugin protocolLibPlugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
                if (protocolLibPlugin != null && protocolLibPlugin.isEnabled()) {
                    getLogger().info("Successfully loaded ProtocolLib support!");
                    if (getConfig().getBoolean("shop.per-player-shop-sign")) {
                        signHooker = new SignHooker(this);
                        getLogger().info("Successfully registered per-player shop sign!");
                    } else {
                        signHooker = null;
                    }
                } else {
                    getLogger().warning("Failed to load ProtocolLib support, fallback to real item display and per-player shop info sign will automatically disable.");
                    signHooker = null;
                    getConfig().set("shop.display-type", 0);
                    saveConfig();
                }
            }
        }
    }

    private void loadChatProcessor() {
        getLogger().info("Loading the Adventure Chat Processor...");
        getLogger().info("Adventure API loaded from: " + Util.getClassPath(Adventure.class));
        getLogger().info("Adventure Bukkit Platform loaded from: " + Util.getClassPath(BukkitAudiences.class));
        getLogger().info("Adventure Text Serializer (Legacy) loaded from: " + Util.getClassPath(LegacyComponentSerializer.class));
        getLogger().info("Adventure Text Serializer (Gson) loaded from: " + Util.getClassPath(GsonComponentSerializer.class));
        getLogger().info("Adventure MiniMessage Lib loaded from: " + Util.getClassPath(LegacyComponentSerializer.class));
    }

    private void loadCommandHandler() {
        /* PreInit for BootError feature */
        this.registerQuickShopCommands();

    }

    /**
     * Tries to load the economy and its core. If this fails, it will try to use vault. If that fails,
     * it will return false.
     *
     * @return true if successful, false if the core is invalid or is not found, and vault cannot be
     * used.
     */

    public boolean loadEcon() {
        try {
            switch (EconomyType.fromID(getConfig().getInt("economy-type"))) {
                case UNKNOWN -> {
                    setupBootError(new BootError(this.getLogger(), "Can't load the Economy provider, invalid value in config.yml."), true);
                    return false;
                }
                case VAULT -> {
                    economy = new Economy_Vault(this);
                    Log.debug("Economy bridge selected: Vault");
                    if (getConfig().getDouble("tax", 0.0d) > 0) {
                        try {
                            String taxAccount = getConfig().getString("tax-account", "tax");
                            if (!taxAccount.isEmpty()) {
                                OfflinePlayer tax;
                                if (CommonUtil.isUUID(taxAccount)) {
                                    tax = Bukkit.getOfflinePlayer(UUID.fromString(taxAccount));
                                } else {
                                    tax = Bukkit.getOfflinePlayer((Objects.requireNonNull(taxAccount)));
                                }
                                Economy_Vault vault = (Economy_Vault) economy;
                                if (vault.isValid()) {
                                    if (!Objects.requireNonNull(vault.getVault()).hasAccount(tax)) {
                                        try {
                                            Log.debug("Tax account doesn't exists: " + tax);
                                            getLogger().warning("QuickShop detected that no tax account exists and will try to create one. If you see any errors, please change the tax-account name in the config.yml to that of the Server owner.");
                                            if (vault.getVault().createPlayerAccount(tax)) {
                                                getLogger().info("Tax account created.");
                                            } else {
                                                getLogger().warning("Cannot create tax-account, please change the tax-account name in the config.yml to that of the server owner");
                                            }
                                        } catch (Exception ignored) {
                                        }
                                        if (!vault.getVault().hasAccount(tax)) {
                                            getLogger().warning("Player for the Tax-account has never played on this server before and we couldn't create an account. This may cause server lag or economy errors, therefore changing the name is recommended. You may ignore this warning if it doesn't cause any issues.");
                                        }
                                    }

                                }
                            }
                        } catch (Exception fail) {
                            Log.debug("Tax account auto-repair failed: " + fail.getMessage());
                        }
                    }
                }
                case GEMS_ECONOMY -> {
                    economy = new Economy_GemsEconomy(this);
                    Log.debug("Economy bridge selected: GemsEconomy");
                }
                case TNE -> {
                    economy = new Economy_TNE(this);
                    Log.debug("Economy bridge selected: The New Economy");
                }
                default -> Log.debug("Economy bridge selected: Service");
            }
            economy = ServiceInjector.getInjectedService(AbstractEconomy.class, economy);
            if (economy == null) {
                Log.debug("No economy bridge found.");
                return false;
            }
            Log.debug("Selected economy bridge: " + economy.getName());
            if (!economy.isValid()) {
                setupBootError(BuiltInSolution.econError(), false);
                return false;
            }

        } catch (Exception e) {
            if (sentryErrorReporter != null) {
                sentryErrorReporter.ignoreThrow();
            }
            getLogger().severe("Something went wrong while trying to load the economy system!");
            getLogger().severe("QuickShop was unable to hook into an economy system (Couldn't find Vault or Reserve)!");
            getLogger().severe("QuickShop can NOT enable properly!");
            setupBootError(BuiltInSolution.econError(), false);
            getLogger().log(Level.SEVERE, "Plugin Listeners have been disabled. Please fix this economy issue.", e);
            return false;
        }
        return true;
    }

    private void loadErrorReporter() {
        try {
            if (!getConfig().getBoolean("auto-report-errors")) {
                Log.debug("Error Reporter has been disabled by the configuration.");
            } else {
                sentryErrorReporter = new RollbarErrorReporter(this);
                Log.debug("Error Reporter has been initialized.");
            }
        } catch (Exception th) {
            getLogger().warning("Cannot load the Rollbar Error Reporter: " + th.getMessage());
            getLogger().warning("Because the error reporter doesn't work, report this error to the developer. Thank you!");
        }
    }

    private void loadItemMatcher() {
        ItemMatcher defItemMatcher = switch (getConfig().getInt("matcher.work-type")) {
            case 1 -> new BukkitItemMatcherImpl(this);
            case 0 -> new QuickShopItemMatcherImpl(this);
            default -> throw new IllegalStateException("Unexpected value: " + getConfig().getInt("matcher.work-type"));
        };
        this.itemMatcher = ServiceInjector.getInjectedService(ItemMatcher.class, defItemMatcher);
    }

    private void loadPlatform() {
        if (PaperLib.isPaper()) {
            this.platform = new PaperPlatform(this.translationMapping);
        } else if (PaperLib.isSpigot()) {
            getLogger().warning("Use Paper to get best performance and enhanced features!");
            getLogger().warning("");
            getLogger().warning("QuickShop-Hikari cannot handle translatable components");
            getLogger().warning("on Spigot platform! Make sure you're using Paper or Paper's fork");
            getLogger().warning("to unlock full functions!");
            getLogger().warning("Due the limitation of Spigot, QuickShop-Hikari running under compatibility mode.");

            this.platform = switch (AbstractSpigotPlatform.getNMSVersion()) {
                case "v1_18_R1" -> new Spigot1181Platform(this, this.translationMapping);
                case "v1_18_R2" -> new Spigot1182Platform(this, this.translationMapping);
                case "v1_19_R1" -> new Spigot1191Platform(this, this.translationMapping);
                case "v1_19_R2" -> new Spigot1193Platform(this, this.translationMapping);
                default -> {
                    getLogger().warning("This server running " + AbstractSpigotPlatform.getNMSVersion() + " not supported by Hikari. (Try update? or Use Paper's fork to get cross-platform compatibility.)");
                    Bukkit.getPluginManager().disablePlugin(this);
                    throw new IllegalStateException("This server running " + AbstractSpigotPlatform.getNMSVersion() + " not supported by Hikari. (Try update? or Use Paper's fork to get cross-platform compatibility.)");
                }
            };
        } else {
            throw new UnsupportedOperationException("Unsupported platform");
        }
    }

    private void loadTextManager() {
        getLogger().info("Loading translations (This may take a while)...");
        try {
            this.textManager = new SimpleTextManager(this);
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            getLogger().log(Level.SEVERE, "Failed to initialize text manager, the QuickShop doesn't compatible with your Server version. Did you up-to-date?", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        textManager.load();
    }

    /**
     * Get the permissionManager as static
     *
     * @return the permission Manager.
     */
    @NotNull
    public PermissionManager perm() {
        return permissionManager;
    }

    private void registerCommunicationChannels() {
        this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
    }

    private void registerListeners() {
        new BlockListener(this, this.shopCache).register();
        new PlayerListener(this).register();
        new WorldListener(this).register();
        // Listeners - We decide which one to use at runtime
        new ChatListener(this).register();
        new ChunkListener(this).register();
        new CustomInventoryListener(this).register();
        new ShopProtectionListener(this, this.shopCache).register();
        new MetricListener(this).register();
        new InternalListener(this).register();
        if (checkIfBungee()) {
            this.bungeeListener = new BungeeListener(this);
            this.bungeeListener.register();
        }
    }

    public void registerQuickShopCommands() {
        commandManager = new SimpleCommandManager(this);
        List<String> customCommands = getConfig().getStringList("custom-commands");
        Command quickShopCommand = new QuickShopCommand("qs", commandManager, new ArrayList<>(new HashSet<>(customCommands)));
        try {
            platform.registerCommand("quickshop-hikari", quickShopCommand);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to register command aliases", e);
        }
        Log.debug("QuickShop command registered with those aliases: " + CommonUtil.list2String(quickShopCommand.getAliases()));
    }

    @Override
    public final void onEnable() {
        if (!this.onLoadCalled) {
            getLogger().severe("FATAL: onLoad has not been called for QuickShop. Trying to fix it... Some integrations may not work properly!");
            try {
                onLoad();
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Failed to fix onLoad", ex);
            }
        }
        Timer enableTimer = new Timer(true);
        getLogger().info("QuickShop " + getFork());
        this.audience = BukkitAudiences.create(this);
        /* Check the running envs is support or not. */
        getLogger().info("Starting plugin self-test, please wait...");
        try (PerfMonitor ignored = new PerfMonitor("Self Test")) {
            runtimeCheck(EnvCheckEntry.Stage.ON_ENABLE);
        }
        getLogger().info("Reading the configuration...");
        initConfiguration();
        getLogger().info("Developers: " + CommonUtil.list2String(this.getDescription().getAuthors()));
        getLogger().info("Original author: Netherfoam, Timtower, KaiNoMood, sandtechnology");
        getLogger().info("Let's start loading the plugin");
        getLogger().info("Chat processor selected: Hardcoded BungeeChat Lib");
        /* Process Metrics and Sentry error reporter. */
        metrics = new Metrics(this, 14281);
        loadErrorReporter();
        loadItemMatcher();
        this.itemMarker = new ItemMarker(this);
        this.shopItemBlackList = new ShopItemBlackList(this);
        Util.initialize();
        load3rdParty();
        //Load the database
        try (PerfMonitor ignored = new PerfMonitor("Initialize database")) {
            initDatabase();
        }
        /* Initalize the tools */
        // Create the shop manager.
        permissionManager = new PermissionManager(this);
        shopPermissionManager = new SimpleShopPermissionManager(this);
        // This should be inited before shop manager
        this.registerDisplayAutoDespawn();
        getLogger().info("Registering commands...");
        loadCommandHandler();
        this.shopManager = new SimpleShopManager(this);
        this.permissionChecker = new PermissionChecker(this);
        // Limit
        this.registerLimitRanks();
        // Limit end
        if (getConfig().getInt("shop.finding.distance") > 100 && getConfig().getBoolean("shop.finding.exclude-out-of-stock")) {
            getLogger().severe("Shop find distance is too high with chunk loading feature turned on! It may cause lag! Pick a number below 100!");
        }
        setupShopCaches();
        signUpdateWatcher = new SignUpdateWatcher();
        //shopContainerWatcher = new ShopContainerWatcher();
        shopSaveWatcher = new ShopDataSaveWatcher(this);
        shopSaveWatcher.runTaskTimerAsynchronously(this, 0, 20L * 60L * 5L);
        /* Load all shops. */
        shopLoader = new ShopLoader(this);
        shopLoader.loadShops();
        bakeShopsOwnerCache();
        getLogger().info("Registering listeners...");
        this.interactionController = new InteractionController(this);
        // Register events
        // Listeners (These don't)
        registerListeners();
        this.shopControlPanelManager.register(new SimpleShopControlPanel());
        this.registerDisplayItem();
        this.registerShopLock();
        getLogger().info("Cleaning MsgUtils...");
        MsgUtil.clean();
        MsgUtil.loadTransactionMessages();
        this.registerUpdater();
        /* Delay the Economy system load, give a chance to let economy system register. */
        /* And we have a listener to listen the ServiceRegisterEvent :) */
        Log.debug("Scheduled economy system loading.");
        getServer().getScheduler().runTaskLater(this, this::loadEcon, 1);
        registerTasks();
        Log.debug("DisplayItem selected: " + AbstractDisplayItem.getNowUsing().name());
        registerCommunicationChannels();
        new QSConfigurationReloadEvent(this).callEvent();
        getLogger().info("QuickShop Loaded! " + enableTimer.stopAndGetTimePassed() + " ms.");
    }

    /**
     * Reloads QuickShops config
     */
    @Override
    public void reloadConfig() {
        super.reloadConfig();
        // Load quick variables
        this.display = this.getConfig().getBoolean("shop.display-items");
        this.priceChangeRequiresFee = this.getConfig().getBoolean("shop.price-change-requires-fee");
        this.displayItemCheckTicks = this.getConfig().getInt("shop.display-items-check-ticks");
        this.allowStack = this.getConfig().getBoolean("shop.allow-stacks");
        this.currency = this.getConfig().getString("currency");
        this.loggingLocation = this.getConfig().getInt("logging.location");
        this.translationMapping = new HashMap<>();
        getConfig().getStringList("custom-translation-key").forEach(str -> {
            String[] split = str.split("=", 0);
            this.translationMapping.put(split[0], split[1]);
        });
        this.translationMapping.putAll(this.addonRegisteredMapping);
        if (this.platform != null) {
            this.platform.updateTranslationMappingSection(this.translationMapping);
        }

        if (StringUtils.isEmpty(this.currency)) {
            this.currency = null;
        }
        if (this.getConfig().getBoolean("logging.enable")) {
            logWatcher = new LogWatcher(this, new File(getDataFolder(), "qs.log"));
        } else {
            logWatcher = null;
        }
        // Schedule this event can be run in next tick.
        Util.mainThreadRun(() -> new QSConfigurationReloadEvent(this).callEvent());
    }

    /**
     * Early than onEnable, make sure instance was loaded in first time.
     */
    @Override
    public final void onLoad() {
        instance = this;
        Bukkit.getServicesManager().register(QuickShopProvider.class, new QuickShopProvider() {
            @Override
            public @NotNull QuickShopAPI getApiInstance() {
                return instance;
            }

            @Override
            public @NotNull Plugin getInstance() {
                return instance;
            }
        }, this, ServicePriority.High);
        // Reset the BootError status to normal.
        this.bootError = null;
        Util.setPlugin(this);
        this.onLoadCalled = true;
        getLogger().info("QuickShop " + getFork() + " - Early boot step - Booting up");
        getReloadManager().register(this);
        //BEWARE THESE ONLY RUN ONCE
        this.buildInfo = new BuildInfo(getResource("BUILDINFO"));
        getLogger().info("Self testing...");
        if (!runtimeCheck(EnvCheckEntry.Stage.ON_LOAD)) {
            return;
        }
        getLogger().info("Reading the configuration...");
        initConfiguration();
        getLogger().info("Loading up platform modules...");
        loadPlatform();
        getLogger().info("Loading player name and unique id mapping...");
        this.playerFinder = new FastPlayerFinder(this);
        setupUnirest();
        loadChatProcessor();
        loadTextManager();
        getLogger().info("Register InventoryWrapper...");
        this.inventoryWrapperRegistry.register(this, this.inventoryWrapperManager);
        getLogger().info("Initializing NexusManager...");
        this.nexusManager = new NexusManager(this);
        getLogger().info("QuickShop " + getFork() + " - Early boot step - Complete");
    }

    @Override
    public final void onDisable() {
        getLogger().info("QuickShop is finishing remaining work, this may need a while...");
        if (sentryErrorReporter != null) {
            getLogger().info("Shutting down error reporter...");
            sentryErrorReporter.unregister();
        }
        if (this.quickShopPAPI != null) {
            getLogger().info("Unregistering PlaceHolderAPI hooks...");
            if (this.quickShopPAPI.unregister()) {
                getLogger().info("Successfully unregistered PlaceholderAPI hook!");
            } else {
                getLogger().info("Unregistering not successful. Was it already unloaded?");
            }
        }
        if (getShopManager() != null) {
            getLogger().info("Unloading all loaded shops...");
            getShopManager().getLoadedShops().forEach(Shop::onUnload);
        }
        if (this.bungeeListener != null) {
            getLogger().info("Disabling the BungeeChat messenger listener.");
            Bukkit.getOnlinePlayers().forEach(player -> this.bungeeListener.notifyForCancel(player));
            this.bungeeListener.unregister();
        }
        if (getShopSaveWatcher() != null) {
            getLogger().info("Stopping shop auto save...");
            getShopSaveWatcher().cancel();
        }
        /* Remove all display items, and any dupes we can find */
        if (shopManager != null) {
            getLogger().info("Cleaning up shop manager...");
            shopManager.clear();
        }
        if (AbstractDisplayItem.getNowUsing() == DisplayType.VIRTUALITEM) {
            getLogger().info("Cleaning up display manager...");
            VirtualDisplayItem.VirtualDisplayItemManager.unload();
        }
        if (logWatcher != null) {
            getLogger().info("Stopping log watcher...");
            logWatcher.close();
        }
        getLogger().info("Shutting down scheduled timers...");
        Bukkit.getScheduler().cancelTasks(this);
        if (calendarWatcher != null) {
            getLogger().info("Shutting down event calendar watcher...");
            calendarWatcher.stop();
        }
        /* Unload UpdateWatcher */
        if (this.updateWatcher != null) {
            getLogger().info("Shutting down update watcher...");
            this.updateWatcher.uninit();
        }
        getLogger().info("Cleanup listeners...");
        HandlerList.unregisterAll(this);
        getLogger().info("Shutting down 3rd-party integrations...");
        unload3rdParty();
        getLogger().info("Cleanup scheduled tasks...");
        Bukkit.getScheduler().cancelTasks(this);
        getLogger().info("Unregistering plugin services...");
        getServer().getServicesManager().unregisterAll(this);
        if (this.getSqlManager() != null) {
            getLogger().info("Shutting down database connections...");
            EasySQL.shutdownManager(this.getSqlManager());
        }
        getLogger().info("Shutting down Unirest instances...");
        Unirest.shutDown(true);
        getLogger().info("Finishing remaining misc work...");
        this.getServer().getMessenger().unregisterIncomingPluginChannel(this, "BungeeCord");
        getLogger().info("All shutdown work has been completed.");
    }

    private void registerTasks() {
        calendarWatcher = new CalendarWatcher(this);
        // shopVaildWatcher.runTaskTimer(this, 0, 20 * 60); // Nobody use it
        signUpdateWatcher.runTaskTimer(this, 0, 10);
        //shopContainerWatcher.runTaskTimer(this, 0, 5); // Nobody use it
        if (logWatcher != null) {
            logWatcher.runTaskTimerAsynchronously(this, 10, 10);
            getLogger().info("Log actions is enabled. Actions will be logged in the qs.log file!");
        }
        this.registerOngoingFee();
        getServer().getScheduler().runTask(this, () -> {
            getLogger().info("Registering bStats metrics...");
            submitMetrics();
        });
        calendarWatcher = new CalendarWatcher(this);
        calendarWatcher.start();
        this.shopPurger = new ShopPurger(this);
        if (getConfig().getBoolean("purge.at-server-startup")) {
            shopPurger.purge();
        }
    }

    private void unload3rdParty() {
        if (this.placeHolderAPI != null && placeHolderAPI.isEnabled() && this.quickShopPAPI != null) {
            this.quickShopPAPI.unregister();
            getLogger().info("Unload PlaceHolderAPI module successfully!");
        }
        if (this.signHooker != null) {
            this.signHooker.unload();
            getLogger().info("Unload SignHooker module successfully!");
        }
        Plugin protocolLibPlugin = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        if (protocolLibPlugin != null && protocolLibPlugin.isEnabled()) {
            ProtocolLibrary.getProtocolManager().removePacketListeners(this);
            getLogger().info("Unload packet listeners successfully!");
        }
    }

    @Override
    public ReloadResult reloadModule() throws Exception {
        registerDisplayAutoDespawn();
        registerOngoingFee();
        registerUpdater();
        registerShopLock();
        registerDisplayItem();
        registerLimitRanks();
        return Reloadable.super.reloadModule();
    }

    private void registerDisplayAutoDespawn() {
        if (this.display && getConfig().getBoolean("shop.display-auto-despawn")) {
            this.displayAutoDespawnWatcher = new DisplayAutoDespawnWatcher(this);
            //BUKKIT METHOD SHOULD ALWAYS EXECUTE ON THE SERVER MAIN THEAD
            this.displayAutoDespawnWatcher.runTaskTimer(this, 20, getConfig().getInt("shop.display-check-time")); // not worth async
            getLogger().warning("Unrecommended use of display-auto-despawn. This feature may have a heavy impact on the server's performance!");
        } else {
            if (this.displayAutoDespawnWatcher != null) {
                this.displayAutoDespawnWatcher.cancel();
                this.displayAutoDespawnWatcher = null;
            }
        }
    }

    private void registerOngoingFee() {
        if (getConfig().getBoolean("shop.ongoing-fee.enable")) {
            ongoingFeeWatcher = new OngoingFeeWatcher(this);
            ongoingFeeWatcher.runTaskTimerAsynchronously(this, 0, getConfig().getInt("shop.ongoing-fee.ticks"));
            getLogger().info("Ongoing fee feature is enabled.");
        } else {
            if (ongoingFeeWatcher != null) {
                ongoingFeeWatcher.cancel();
                ongoingFeeWatcher = null;
            }
        }
    }

    private void registerUpdater() {
        if (this.getConfig().getBoolean("updater", true)) {
            updateWatcher = new UpdateWatcher();
            updateWatcher.init();
        } else {
            if (updateWatcher != null) {
                updateWatcher.uninit();
                updateWatcher = null;
            }
        }
    }

    private void registerShopLock() {
        if (getConfig().getBoolean("shop.lock")) {
            new LockListener(this, this.shopCache).register();
        } else {
            Util.unregisterListenerClazz(this, LockListener.class);
        }
    }

    private void registerDisplayItem() {
        if (this.display && AbstractDisplayItem.getNowUsing() != DisplayType.VIRTUALITEM) {
            if (getDisplayItemCheckTicks() > 0) {
                if (getConfig().getInt("shop.display-items-check-ticks") < 3000) {
                    getLogger().severe("Shop.display-items-check-ticks is too low! It may cause HUGE lag! Pick a number > 3000");
                }
                getLogger().info("Registering DisplayCheck task....");
                getServer().getScheduler().runTaskTimer(this, () -> {
                    for (Shop shop : getShopManager().getLoadedShops()) {
                        //Shop may be deleted or unloaded when iterating
                        if (shop.isDeleted() || !shop.isLoaded()) {
                            continue;
                        }
                        shop.checkDisplay();
                    }
                }, 1L, getDisplayItemCheckTicks());
            } else if (getDisplayItemCheckTicks() == 0) {
                getLogger().info("shop.display-items-check-ticks was set to 0. Display Check has been disabled");
            } else {
                getLogger().severe("shop.display-items-check-ticks has been set to an invalid value. Please use a value above 3000.");
            }
            new DisplayProtectionListener(this, this.shopCache).register();
        } else {
            Util.unregisterListenerClazz(this, DisplayProtectionListener.class);
        }
    }

    private void registerLimitRanks() {
        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        ConfigurationSection limitCfg = yamlConfiguration.getConfigurationSection("limits");
        if (limitCfg != null) {
            this.limit = limitCfg.getBoolean("use", false);
            limitCfg = limitCfg.getConfigurationSection("ranks");
            for (String key : Objects.requireNonNull(limitCfg).getKeys(true)) {
                limits.put(key, limitCfg.getInt(key));
            }
        } else {
            this.limit = false;
            limits.clear();
        }
    }

    private boolean runtimeCheck(@NotNull EnvCheckEntry.Stage stage) {
        environmentChecker = new EnvironmentChecker(this);
        ResultReport resultReport = environmentChecker.run(stage);
        StringJoiner joiner = new StringJoiner("\n", "", "");
        if (resultReport.getFinalResult().ordinal() > CheckResult.WARNING.ordinal()) {
            for (Entry<EnvCheckEntry, ResultContainer> result : resultReport.getResults().entrySet()) {
                if (result.getValue().getResult().ordinal() > CheckResult.WARNING.ordinal()) {
                    joiner.add(String.format("- [%s/%s] %s", result.getValue().getResult().getDisplay(), result.getKey().name(), result.getValue().getResultMessage()));
                }
            }
        }
        // Check If we need kill the server or disable plugin

        switch (resultReport.getFinalResult()) {
            case DISABLE_PLUGIN -> {
                Bukkit.getPluginManager().disablePlugin(this);
                return false;
            }
            case STOP_WORKING -> {
                setupBootError(new BootError(this.getLogger(), joiner.toString()), true);
                PluginCommand command = getCommand("qs");
                if (command != null) {
                    Util.mainThreadRun(() -> command.setTabCompleter(this)); //Disable tab completer
                }
            }
            default -> {
            }
        }
        return true;
    }

    /**
     * Mark plugins stop working
     *
     * @param bootError           reason
     * @param unregisterListeners should we disable all listeners?
     */
    public void setupBootError(BootError bootError, boolean unregisterListeners) {
        this.bootError = bootError;
        if (unregisterListeners) {
            HandlerList.unregisterAll(this);
        }
        Bukkit.getScheduler().cancelTasks(this);
    }

    /**
     * Setup the database
     *
     * @return The setup result
     */
    private boolean setupDatabase() {
        getLogger().info("Setting up database...");

        HikariConfig config = HikariUtil.createHikariConfig();

        try {
            databaseDriverType = DatabaseDriverType.MYSQL;
            ConfigurationSection dbCfg = getConfig().getConfigurationSection("database");
            if (Objects.requireNonNull(dbCfg).getBoolean("mysql")) {
                // MySQL database - Required database be created first.
                dbPrefix = dbCfg.getString("prefix");
                if (dbPrefix == null || "none".equals(dbPrefix)) {
                    dbPrefix = "";
                }
                String user = dbCfg.getString("user");
                String pass = dbCfg.getString("password");
                String host = dbCfg.getString("host");
                String port = dbCfg.getString("port");
                String database = dbCfg.getString("database");
                boolean useSSL = dbCfg.getBoolean("usessl");
                config.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?useSSL=" + useSSL);
                config.setUsername(user);
                config.setPassword(pass);
                this.sqlManager = new SQLManagerImpl(new HikariDataSource(config), "QuickShop-Hikari-SQLManager");
            } else {
                // H2 database - Doing this handles file creation
                databaseDriverType = DatabaseDriverType.H2;
                Log.debug("Registering JDBC H2 driver...");
                Driver.load();
                getLogger().info("Create database backup...");
                new DatabaseBackupUtil().backup();
                String driverClassName = Driver.class.getName();
                Log.debug("Setting up H2 driver class name to: " + driverClassName);
                config.setDriverClassName(driverClassName);
                config.setJdbcUrl("jdbc:h2:" + new File(this.getDataFolder(), "shops").getCanonicalFile().getAbsolutePath() + ";DB_CLOSE_DELAY=-1;MODE=MYSQL");
                this.sqlManager = new SQLManagerImpl(new HikariDataSource(config), "QuickShop-Hikari-SQLManager");
                this.sqlManager.executeSQL("SET MODE=MYSQL"); // Switch to MySQL mode
            }
            //this.sqlManager.setDebugMode(Util.isDevMode());
            this.sqlManager.setExecutorPool(QuickExecutor.getDatabaseExecutor());
            // Make the database up to date
            this.databaseHelper = new SimpleDatabaseHelperV2(this, this.sqlManager, this.getDbPrefix());
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error when connecting to the database", e);
            if (setupDBonEnableding) {
                bootError = BuiltInSolution.databaseError();
            }
            return false;
        }
    }

    private void setupShopCaches() {
        if (getConfig().getBoolean("use-caching")) {
            this.shopCache = new Cache(this);
        } else {
            this.shopCache = null;
        }
    }

    private void setupUnirest() {
        getLogger().info("Initialing Unirest http request library...");
        Unirest.config()
                .concurrency(10, 5)
                .setDefaultHeader("User-Agent", "QuickShop/" + getFork() + "-" + getDescription().getVersion() + " Java/" + System.getProperty("java.version"));
        Unirest.config().verifySsl(Util.parsePackageProperly("verifySSL").asBoolean());
        if (Util.parsePackageProperly("proxyHost").isPresent()) {
            Unirest.config().proxy(Util.parsePackageProperly("proxyHost").asString("127.0.0.1"), Util.parsePackageProperly("proxyPort").asInteger(1080));
        }
        if (Util.parsePackageProperly("proxyUsername").isPresent()) {
            Unirest.config().proxy(Util.parsePackageProperly("proxyHost").asString("127.0.0.1"), Util.parsePackageProperly("proxyPort").asInteger(1080), Util.parsePackageProperly("proxyUsername").asString(""), Util.parsePackageProperly("proxyPassword").asString(""));
        }
    }

    private void submitMetrics() {
        if (!getConfig().getBoolean("disabled-metrics")) {
            // Use internal Metric class not Maven for solve plugin name issues
            // Version
            metrics.addCustomChart(new Metrics.SimplePie("use_display_items", () -> CommonUtil.boolean2Status(getConfig().getBoolean("shop.display-items"))));
            metrics.addCustomChart(new Metrics.SimplePie("use_locks", () -> CommonUtil.boolean2Status(getConfig().getBoolean("shop.lock"))));
            metrics.addCustomChart(new Metrics.SimplePie("use_display_auto_despawn", () -> String.valueOf(getConfig().getBoolean("shop.display-auto-despawn"))));
            metrics.addCustomChart(new Metrics.SimplePie("display_type", () -> AbstractDisplayItem.getNowUsing().name()));
            metrics.addCustomChart(new Metrics.SimplePie("itemmatcher_type", () -> this.getItemMatcher().getName()));
            metrics.addCustomChart(new Metrics.SimplePie("use_stack_item", () -> String.valueOf(this.isAllowStack())));
            metrics.addCustomChart(new Metrics.SingleLineChart("shops_created_on_all_servers", () -> this.getShopManager().getAllShops().size()));
        } else {
            getLogger().info("You disabled metrics, Skipping...");
        }
    }

    private boolean checkIfBungee() {
        if (Util.parsePackageProperly("forceBungeeCord").asBoolean()) {
            return true;
        }
        ConfigurationSection section = getServer().spigot().getConfig().getConfigurationSection("settings");
        if (section == null) {
            return false;
        }
        return section.getBoolean("settings.bungeecord");
    }

    public @NotNull TextManager text() {
        return this.textManager;
    }

    private void updateConfig() {
        new ConfigurationUpdater(this).update(new ConfigUpdateScript(getConfig(), this));
    }

    public enum DatabaseDriverType {
        MYSQL,
        H2
    }

}
