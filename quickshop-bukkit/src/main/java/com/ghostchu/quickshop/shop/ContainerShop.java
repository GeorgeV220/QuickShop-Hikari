package com.ghostchu.quickshop.shop;

import com.ghostchu.quickshop.QuickShop;
import com.ghostchu.quickshop.ServiceInjector;
import com.ghostchu.quickshop.api.economy.Benefit;
import com.ghostchu.quickshop.api.event.*;
import com.ghostchu.quickshop.api.inventory.InventoryWrapper;
import com.ghostchu.quickshop.api.inventory.InventoryWrapperManager;
import com.ghostchu.quickshop.api.localization.text.ProxiedLocale;
import com.ghostchu.quickshop.api.serialize.BlockPos;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopInfoStorage;
import com.ghostchu.quickshop.api.shop.ShopModerator;
import com.ghostchu.quickshop.api.shop.ShopType;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermission;
import com.ghostchu.quickshop.api.shop.permission.BuiltInShopPermissionGroup;
import com.ghostchu.quickshop.common.util.CommonUtil;
import com.ghostchu.quickshop.common.util.QuickExecutor;
import com.ghostchu.quickshop.database.bean.SimpleDataRecord;
import com.ghostchu.quickshop.economy.SimpleEconomyTransaction;
import com.ghostchu.quickshop.shop.datatype.ShopSignPersistentDataType;
import com.ghostchu.quickshop.shop.display.AbstractDisplayItem;
import com.ghostchu.quickshop.shop.display.RealDisplayItem;
import com.ghostchu.quickshop.shop.display.VirtualDisplayItem;
import com.ghostchu.quickshop.util.JsonUtil;
import com.ghostchu.quickshop.util.MsgUtil;
import com.ghostchu.quickshop.util.Util;
import com.ghostchu.quickshop.util.logger.Log;
import com.ghostchu.quickshop.util.logging.container.ShopRemoveLog;
import com.ghostchu.quickshop.util.performance.PerfMonitor;
import com.ghostchu.simplereloadlib.ReloadResult;
import com.ghostchu.simplereloadlib.Reloadable;
import com.google.common.collect.ImmutableList;
import io.papermc.lib.PaperLib;
import lombok.EqualsAndHashCode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * ChestShop core
 */
@EqualsAndHashCode
public class ContainerShop implements Shop, Reloadable {
    // We use deprecated method to create a fake quickshop-reremake namespace to trick bukkit to access legacy data.
    @SuppressWarnings({"AliDeprecation", "deprecation"})
    private static final NamespacedKey LEGACY_SHOP_NAMESPACED_KEY = new NamespacedKey("quickshop", "shopsign");
    @NotNull
    private final Location location;
    private final YamlConfiguration extra;
    @EqualsAndHashCode.Exclude
    private final QuickShop plugin;
    @EqualsAndHashCode.Exclude
    private final UUID runtimeRandomUniqueId = UUID.randomUUID();
    @NotNull
    private final Map<UUID, String> playerGroup;
    private long shopId;
    private UUID owner;
    private double price;
    private ShopType shopType;
    private boolean unlimited;
    @NotNull
    private ItemStack item;
    @NotNull
    private ItemStack originalItem;
    @Nullable
    @EqualsAndHashCode.Exclude
    private AbstractDisplayItem displayItem;
    @EqualsAndHashCode.Exclude
    private volatile boolean isLoaded = false;
    @EqualsAndHashCode.Exclude
    private volatile boolean isDeleted = false;
    @EqualsAndHashCode.Exclude
    private volatile boolean isLeftShop = false;
    @EqualsAndHashCode.Exclude
    private volatile boolean createBackup = false;
    @EqualsAndHashCode.Exclude
    private InventoryPreview inventoryPreview = null;
    @EqualsAndHashCode.Exclude
    private ContainerShop attachedShop;
    @EqualsAndHashCode.Exclude
    private boolean isDisplayItemChanged = false;
    @EqualsAndHashCode.Exclude
    private boolean dirty;
    @EqualsAndHashCode.Exclude
    private boolean updating = false;
    @Nullable
    private String currency;
    private boolean disableDisplay;
    private UUID taxAccount;
    @NotNull
    private String inventoryWrapperProvider;
    @EqualsAndHashCode.Exclude
    private InventoryWrapper inventoryWrapper;
    @NotNull
    private String symbolLink;
    @Nullable
    private String shopName;

    @NotNull
    private Benefit benefit;

    ContainerShop(@NotNull ContainerShop s) {
        Util.ensureThread(false);
        this.shopId = s.shopId;
        this.shopType = s.shopType;
        this.item = s.item.clone();
        this.originalItem = s.originalItem.clone();
        this.location = s.location.clone();
        this.plugin = s.plugin;
        this.unlimited = s.unlimited;
        this.owner = s.owner;
        this.price = s.price;
        this.isLoaded = s.isLoaded;
        this.isDeleted = s.isDeleted;
        this.createBackup = s.createBackup;
        this.extra = s.extra;
        this.dirty = true;
        this.inventoryPreview = null;
        this.currency = s.currency;
        this.disableDisplay = s.disableDisplay;
        this.taxAccount = s.taxAccount;
        this.inventoryWrapper = s.inventoryWrapper;
        this.inventoryWrapperProvider = s.inventoryWrapperProvider;
        this.symbolLink = s.symbolLink;
        this.shopName = s.shopName;
        this.playerGroup = s.playerGroup;
        this.benefit = s.benefit;

        initDisplayItem();
    }

    private void initDisplayItem() {
        Util.ensureThread(false);
        try {
            if (plugin.isDisplayEnabled() && !isDisableDisplay()) {
                DisplayProvider provider = ServiceInjector.getInjectedService(DisplayProvider.class, null);
                if (provider != null) {
                    displayItem = provider.provide(this);
                } else {
                    this.displayItem = switch (AbstractDisplayItem.getNowUsing()) {
                        case REALITEM -> new RealDisplayItem(this);
                        default -> new VirtualDisplayItem(this);
                    };
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to init display item for shop " + this + ", display item init failed!", e);
        }
    }

    /**
     * Adds a new shop. You need call ShopManager#loadShop if you create from outside of
     * ShopLoader.
     *
     * @param location  The location of the chest block
     * @param price     The cost per item
     * @param item      The itemstack with the properties we want. This is .cloned, no need to worry
     *                  about references
     * @param owner     The shop owner
     * @param type      The shop type
     * @param unlimited The unlimited
     * @param plugin    The plugin instance
     * @param extra     The extra data saved by addon
     */
    public ContainerShop(
            @NotNull QuickShop plugin,
            long shopId,
            @NotNull Location location,
            double price,
            @NotNull ItemStack item,
            @NotNull UUID owner,
            boolean unlimited,
            @NotNull ShopType type,
            @NotNull YamlConfiguration extra,
            @Nullable String currency,
            boolean disableDisplay,
            @Nullable UUID taxAccount,
            @NotNull String inventoryWrapperProvider,
            @NotNull String symbolLink,
            @Nullable String shopName,
            @NotNull Map<UUID, String> playerGroup,
            @NotNull Benefit shopBenefit) {
        Util.ensureThread(false);
        this.shopId = shopId;
        this.shopName = shopName;
        this.location = location;
        this.price = price;
        this.benefit = shopBenefit;


        // Upgrade the shop moderator
        this.owner = owner;
        this.item = item.clone();
        this.originalItem = item.clone();
        this.plugin = plugin;
        this.playerGroup = new HashMap<>(playerGroup);
        if (!plugin.isAllowStack()) {
            this.item.setAmount(1);
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta.hasDisplayName() && meta.getDisplayName().matches("\\{.*}")) {
                //https://hub.spigotmc.org/jira/browse/SPIGOT-5964
                meta.setDisplayName(meta.getDisplayName());
                //Correct both items
                item.setItemMeta(meta);
                this.item.setItemMeta(meta);
            }
        }
        this.shopType = type;
        this.unlimited = unlimited;
        this.extra = extra;
        this.currency = currency;
        this.disableDisplay = disableDisplay;
        this.taxAccount = taxAccount;
        this.dirty = false;
        //noinspection ConstantConditions
        if (symbolLink == null) {
            throw new IllegalArgumentException("SymbolLink cannot be null");
        }
        //noinspection ConstantConditions
        if (inventoryWrapperProvider == null) {
            throw new IllegalArgumentException("InventoryWrapperProvider cannot be null");
        }
        this.symbolLink = symbolLink;
        this.inventoryWrapperProvider = inventoryWrapperProvider;
        initDisplayItem();
        updateShopData();
        // ContainerShop constructor is not allowed to write any persistent data to disk
    }

    private void updateShopData() {
        ConfigurationSection section = getExtra(plugin);
        if (section.getString("currency") != null) {
            this.currency = section.getString("currency");
            section.set("currency", null);
            Log.debug("Shop " + this + " currency data upgrade successful.");
        }
        setDirty();
    }

    /**
     * Add an item to shops chest.
     *
     * @param item   The itemstack. The amount does not matter, just everything else
     * @param amount The amount to add to the shop.
     */
    @Override
    public void add(@NotNull ItemStack item, int amount) {
        Util.ensureThread(false);
        if (this.unlimited) {
            return;
        }
        item = item.clone();
        int itemMaxStackSize = Util.getItemMaxStackSize(item.getType());
        InventoryWrapper inv = this.getInventory();
        if (inv == null) {
            throw new IllegalArgumentException("Failed to add item to shop " + this + ", the inventory is null!");
        }
        int remains = amount;
        while (remains > 0) {
            int stackSize = Math.min(remains, itemMaxStackSize);
            item.setAmount(stackSize);
            Objects.requireNonNull(inv).addItem(item);
            remains -= stackSize;
        }
        this.setSignText();
    }

    @SuppressWarnings("removal")
    @Override
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    public boolean addStaff(@NotNull UUID player) {
        Util.ensureThread(false);
        setPlayerGroup(player, BuiltInShopPermissionGroup.STAFF);
        setDirty();
        return true;
    }

    /**
     * Buys amount of item from Player p. Does NOT check our inventory, or balances
     *
     * @param buyer          The player to buy from
     * @param buyerInventory The buyer's inventory
     * @param loc2Drop       The location to drop items if inventory are full
     * @param amount         The amount to buy
     */
    @Override
    public void buy(@NotNull UUID buyer, @NotNull InventoryWrapper buyerInventory,
                    @NotNull Location loc2Drop, int amount) throws Exception {
        Util.ensureThread(false);
        amount = amount * item.getAmount();
        if (amount < 0) {
            this.sell(buyer, buyerInventory, loc2Drop, -amount);
            return;
        }
        if (this.isUnlimited()) {
            SimpleInventoryTransaction transaction = SimpleInventoryTransaction
                    .builder()
                    .from(buyerInventory)
                    .to(null) // To void
                    .item(this.getItem())
                    .amount(amount)
                    .build();
            if (!transaction.failSafeCommit()) {
                if (plugin.getSentryErrorReporter() != null) {
                    plugin.getSentryErrorReporter().ignoreThrow();
                }
                throw new IllegalStateException("Failed to commit transaction! Economy Error Response:" + transaction.getLastError());
            }
        } else {
            InventoryWrapper chestInv = this.getInventory();
            if (chestInv == null) {
                plugin.getLogger().warning("Failed to process buy, reason: " + item + " x" + amount + " to shop " + this + ": Inventory null.");
                Log.debug("Failed to process buy, reason: " + item + " x" + amount + " to shop " + this + ": Inventory null.");
                return;
            }
            SimpleInventoryTransaction transaction = SimpleInventoryTransaction
                    .builder()
                    .from(buyerInventory)
                    .to(chestInv) // To void
                    .item(this.getItem())
                    .amount(amount)
                    .build();
            if (!transaction.failSafeCommit()) {
                if (plugin.getSentryErrorReporter() != null) {
                    plugin.getSentryErrorReporter().ignoreThrow();
                }
                throw new IllegalStateException("Failed to commit transaction! Economy Error Response:" + transaction.getLastError());
            }
        }
        //Update sign
        this.setSignText(plugin.text().findRelativeLanguages(buyer));
        if (attachedShop != null) {
            attachedShop.setSignText(plugin.text().findRelativeLanguages(buyer));
        }
    }

    @Override
    public void checkDisplay() {
        Util.ensureThread(false);
        if (!plugin.isDisplayEnabled() || this.disableDisplay || !this.isLoaded || this.isDeleted()) { // FIXME: Reinit scheduler on reloading config
            if (this.displayItem != null && this.displayItem.isSpawned()) {
                this.displayItem.remove();
            }
            return;
        }

        if (plugin.getConfig().getBoolean("shop.display-center", false)) {
            //FIXME: This may affect the performance
            updateAttachedShop();

            if (isLeftShop) {
                if (displayItem != null) {
                    displayItem.remove();
                }
                if (attachedShop != null) {
                    attachedShop.refresh();
                }
                return;
            }
        }

        if (this.displayItem == null) {
            Log.debug("Warning: DisplayItem is null, this shouldn't happened...");
            StackTraceElement traceElements = Thread.currentThread().getStackTrace()[2];
            Log.debug("Call from: " + traceElements.getClassName() + "#" + traceElements.getMethodName() + "%" + traceElements.getLineNumber());
            return;
        }

        if (!this.displayItem.isSpawned()) {
            /* Not spawned yet. */
            displayItem.spawn();
        } else {
            /* If not spawned, we didn't need check these, only check them when we need. */
            if (this.displayItem.checkDisplayNeedRegen()) {
                this.displayItem.fixDisplayNeedRegen();
            } else {
                /* If display was regened, we didn't need check it moved, performance! */
                if (this.displayItem.checkDisplayIsMoved()) {
                    this.displayItem.fixDisplayMoved();
                }
            }
        }
        /* Dupe is always need check, if enabled display */
        this.displayItem.removeDupe();
    }

    @Override
    public void claimShopSign(@NotNull Sign sign) {
        if (!sign.getPersistentDataContainer().has(Shop.SHOP_NAMESPACED_KEY, ShopSignPersistentDataType.INSTANCE)) {
            sign.getPersistentDataContainer().set(Shop.SHOP_NAMESPACED_KEY, ShopSignPersistentDataType.INSTANCE, saveToShopSignStorage());
            sign.update();
        }
    }

    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    @Override
    public void clearStaffs() {
        Util.ensureThread(false);
        this.playersCanAuthorize(BuiltInShopPermissionGroup.STAFF).forEach(this.playerGroup::remove);
        setDirty();
    }

    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    @Override
    public boolean delStaff(@NotNull UUID player) {
        Util.ensureThread(false);
        if (getPlayerGroup(player).equals(BuiltInShopPermissionGroup.STAFF.getNamespacedNode())) {
            setPlayerGroup(player, BuiltInShopPermissionGroup.EVERYONE);
            setDirty();
        }
        return true;
    }

    /**
     * Deletes the shop from the list of shops and queues it for database
     */
    @Override
    public void delete() {
        Util.ensureThread(false);
        delete(false);
    }

    /**
     * Deletes the shop from the list of shops and queues it for database deletion
     *
     * @param memoryOnly whether to delete from database
     */
    @Override
    public void delete(boolean memoryOnly) {
        Util.ensureThread(false);
        // Get a copy of the attached shop to save it from deletion
        ContainerShop neighbor = getAttachedShop();
        ShopDeleteEvent shopDeleteEvent = new ShopDeleteEvent(this, memoryOnly);
        if (Util.fireCancellableEvent(shopDeleteEvent)) {
            Log.debug("Shop deletion was canceled because a plugin canceled it.");
            return;
        }
        setDirty();
        isDeleted = true;
        // Unload the shop
        if (isLoaded) {
            this.onUnload();
        }
        if (memoryOnly) {
            // Delete it from memory
            plugin.getShopManager().removeShop(this);
        } else {
            // Delete the signs around it
            for (Sign s : this.getSigns()) {
                s.getBlock().setType(Material.AIR);
            }
            // Delete it from the database
            // Refund if necessary
            if (plugin.getConfig().getBoolean("shop.refund")) {
                double cost = plugin.getConfig().getDouble("shop.cost");
                SimpleEconomyTransaction transaction;
                if (plugin.getConfig().getBoolean("shop.refund-from-tax-account", false) && taxAccount != null) {
                    cost = Math.min(cost, plugin.getEconomy().getBalance(taxAccount, this.getLocation().getWorld(), plugin.getCurrency()));
                    transaction =
                            SimpleEconomyTransaction.builder()
                                    .amount(cost)
                                    .core(plugin.getEconomy())
                                    .currency(plugin.getCurrency())
                                    .world(this.getLocation().getWorld())
                                    .from(taxAccount)
                                    .to(this.getOwner())
                                    .build();
                } else {
                    transaction =
                            SimpleEconomyTransaction.builder()
                                    .amount(cost)
                                    .core(plugin.getEconomy())
                                    .currency(plugin.getCurrency())
                                    .world(this.getLocation().getWorld())
                                    .to(this.getOwner())
                                    .build();
                }
                if (!transaction.failSafeCommit()) {
                    plugin.getLogger().warning("Shop deletion refund failed. Reason: " + transaction.getLastError());
                }
            }
            plugin.getShopManager().removeShop(this);
            Location loc = getLocation();
            try {
                plugin.getDatabaseHelper().removeShopMap(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            } catch (Exception e) {
                e.printStackTrace();
                plugin.getLogger().warning("Failed to remove the shop mapping from database.");
            }
        }
        // Use that copy we saved earlier (which is now deleted) to refresh it's now alone neighbor
        if (neighbor != null) {
            neighbor.refresh();
        }
    }

    @Override
    public ContainerShop getAttachedShop() {
        return attachedShop;
    }

    /**
     * Gets the currency that shop use
     *
     * @return The currency name
     */
    @Override
    public @Nullable String getCurrency() {
        return this.currency;
    }

    /**
     * Sets the currency that shop use
     *
     * @param currency The currency name; null to use default currency
     */
    @Override
    public void setCurrency(@Nullable String currency) {
        if (Objects.equals(this.currency, currency)) {
            return;
        }
        this.currency = currency;
        setDirty();
    }

    /**
     * @return The durability of the item
     */
    @Override
    public short getDurability() {
        return (short) ((Damageable) this.item.getItemMeta()).getDamage();
    }

    /**
     * Gets the plugin's k-v map to storage the data. It is spilt by plugin name, different name
     * have different map, the data won't conflict. But if you plugin name is too common, add a
     * prefix will be a good idea.
     *
     * @param plugin Plugin instance
     * @return The data table
     */
    @Override
    public @NotNull ConfigurationSection getExtra(@NotNull Plugin plugin) {
        ConfigurationSection section = extra.getConfigurationSection(plugin.getName());
        if (section == null) {
            section = extra.createSection(plugin.getName());
        }
        return section;
    }

    /**
     * @return The chest this shop is based on.
     */
    @Override
    public @Nullable InventoryWrapper getInventory() {
        if (inventoryWrapper == null) {
            Util.ensureThread(false);
            Log.debug("SymbolLink Applying: " + symbolLink);
            try {
                inventoryWrapper = locateInventory(symbolLink);
            } catch (Exception e) {
                Log.debug("Cannot locate the Inventory with symbol link: " + symbolLink + ", provider: " + inventoryWrapperProvider);
                return null;
            }
        }
        if (inventoryWrapper.isValid()) {
            return inventoryWrapper;
        }
        if (!createBackup) {
            createBackup = false;
            if (createBackup) {
                this.delete(false);
            }
        } else {
            this.delete(true);
        }
        plugin.logEvent(new ShopRemoveLog(CommonUtil.getNilUniqueId(), "Inventory Invalid", this.saveToInfoStorage()));
        Log.debug("Inventory doesn't exist anymore: " + this + " shop was deleted.");
        return null;
    }

    @Override
    public @NotNull String getInventoryWrapperProvider() {
        return inventoryWrapperProvider;
    }

    /**
     * @return Returns a dummy itemstack of the item this shop is selling.
     */
    @Override
    public @NotNull ItemStack getItem() {
        return item;
    }

    @Override
    public void setItem(@NotNull ItemStack item) {
        Util.ensureThread(false);
        ShopItemChangeEvent event = new ShopItemChangeEvent(this, this.item, item);
        if (Util.fireCancellableEvent(event)) {
            Log.debug("A plugin cancelled the item change event.");
            return;
        }
        this.item = item;
        this.originalItem = item;
        notifyDisplayItemChange();
        setDirty();
        refresh();
    }

    /**
     * @return The location of the shops chest
     */
    @Override
    public @NotNull Location getLocation() {
        return this.location;
    }

    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    @Override
    public @NotNull ShopModerator getModerator() {
        return new SimpleShopModerator(this.getOwner(), ImmutableList.copyOf(playersCanAuthorize(BuiltInShopPermissionGroup.STAFF)));
    }

    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    @Override
    public void setModerator(@NotNull ShopModerator shopModerator) {
        Util.ensureThread(false);
        setDirty();
    }

    /**
     * @return The name of the player who owns the shop.
     */
    @Override
    public @NotNull UUID getOwner() {
        return this.owner;
    }

    /**
     * Changes the owner of this shop to the given player.
     *
     * @param owner the new owner
     */
    @Override
    public void setOwner(@NotNull UUID owner) {
        Util.ensureThread(false);
        if (this.owner.equals(owner)) {
            return;
        }
        this.owner = owner;
        setSignText(plugin.getTextManager().findRelativeLanguages(owner));
    }

    /**
     * Gets registered to this shop's permission audiences.
     *
     * @return registered audiences
     */
    @Override
    public @NotNull Map<UUID, String> getPermissionAudiences() {
        return Map.copyOf(playerGroup);
    }

    /**
     * Gets the player's group in this shop
     *
     * @param player the player
     * @return the group
     */
    @Override
    public @NotNull String getPlayerGroup(@NotNull UUID player) {
        if (player.equals(getOwner())) {
            return BuiltInShopPermissionGroup.ADMINISTRATOR.getNamespacedNode();
        }
        String group = this.playerGroup.getOrDefault(player, BuiltInShopPermissionGroup.EVERYONE.getNamespacedNode());
        if (plugin.getShopPermissionManager().hasGroup(group)) {
            return group;
        }
        return BuiltInShopPermissionGroup.EVERYONE.getNamespacedNode();
    }

    /**
     * @return The price per item this shop is selling
     */
    @Override
    public double getPrice() {
        return this.price;
    }

    /**
     * Sets the price of the shop.
     *
     * @param price The new price of the shop.
     */
    @Override
    public void setPrice(double price) {
        if (this.price == price) {
            return;
        }
        Util.ensureThread(false);
        ShopPriceChangeEvent event = new ShopPriceChangeEvent(this, this.price, price);
        if (Util.fireCancellableEvent(event)) {
            Log.debug("A plugin cancelled the price change event.");
            return;
        }
        this.price = price;
        setDirty();
        setSignText();
    }

    /**
     * Returns the number of free spots in the chest for the particular item.
     *
     * @return remaining space
     */
    @Override
    public int getRemainingSpace() {
        Util.ensureThread(false);
        if (this.unlimited) {
            return -1;
        }
        if (this.getInventory() == null) {
            Log.debug("Failed to calc RemainingSpace for shop " + this + ": Inventory null.");
            return 0;
        }
        int space = Util.countSpace(this.getInventory(), this);
        new ShopInventoryCalculateEvent(this, space, -1).callEvent();
        Log.debug("Space count is: " + space);
        return space;
    }

    /**
     * Returns the number of items this shop has in stock.
     *
     * @return The number of items available for purchase.
     */
    @Override
    public int getRemainingStock() {
        Util.ensureThread(false);
        if (this.unlimited) {
            return -1;
        }
        if (this.getInventory() == null) {
            Log.debug("Failed to calc RemainingStock for shop " + this + ": Inventory null.");
            return 0;
        }
        int stock = Util.countItems(this.getInventory(), this);
        new ShopInventoryCalculateEvent(this, -1, stock).callEvent();
        return stock;
    }

    /**
     * WARNING: This UUID will changed after plugin reload, shop reload or server restart DO NOT USE
     * IT TO STORE DATA!
     *
     * @return Random UUID
     */
    @Override
    public @NotNull UUID getRuntimeRandomUniqueId() {
        return this.runtimeRandomUniqueId;
    }

    @Override
    public long getShopId() {
        return this.shopId;
    }

    @Override
    public void setShopId(long newId) {
        if (this.shopId != -1) {
            throw new IllegalStateException("Cannot set shop id once it fully created.");
        }
        this.shopId = newId;
    }

    /**
     * Gets this shop name that set by player
     *
     * @return Shop name, or null if not set
     */
    @Override
    public @Nullable String getShopName() {
        return this.shopName;
    }

    /**
     * Sets shop name
     *
     * @param shopName shop name, null to remove currently name
     */
    @Override
    public void setShopName(@Nullable String shopName) {
        if (StringUtils.equals(this.shopName, shopName)) {
            return;
        }
        this.shopName = shopName;
        setDirty();
    }

    /**
     * Getting the item stacking amount of the shop.
     *
     * @return The item stacking amount of the shop.
     */
    @Override
    public int getShopStackingAmount() {
        if (isStackingShop()) {
            return item.getAmount();
        }
        return 1;
    }

    @Override
    public @NotNull ShopType getShopType() {
        return this.shopType;
    }

    /**
     * Changes a shop type to Bu ying or Selling. Also updates the signs nearby.
     *
     * @param newShopType The new type (ShopType.BUYING or ShopType.SELLING)
     */
    @Override
    public void setShopType(@NotNull ShopType newShopType) {
        Util.ensureThread(false);
        if (this.shopType == newShopType) {
            return; //Ignore if there actually no changes
        }
        if (Util.fireCancellableEvent(new ShopTypeChangeEvent(this, this.shopType, newShopType))) {
            Log.debug(
                    "Some addon cancelled shop type changes, target shop: " + this);
            return;
        }
        this.shopType = newShopType;
        this.setSignText();
        setDirty();
    }

    @Override
    public List<Component> getSignText(@NotNull ProxiedLocale locale) {
        Util.ensureThread(false);
        List<Component> lines = new ArrayList<>();
        //Line 1
        String headerKey = inventoryAvailable() ? "signs.header-available" : "signs.header-unavailable";
        lines.add(plugin.text().of(headerKey, this.ownerName(false, locale)).forLocale(locale.getLocale()));
        //Line 2
        String tradingStringKey;
        String noRemainingStringKey;
        int shopRemaining;

        switch (shopType) {
            case BUYING -> {
                shopRemaining = getRemainingSpace();
                tradingStringKey = isStackingShop() ? "signs.stack-buying" : "signs.buying";
                noRemainingStringKey = "signs.out-of-space";
            }
            case SELLING -> {
                shopRemaining = getRemainingStock();
                tradingStringKey = isStackingShop() ? "signs.stack-selling" : "signs.selling";
                noRemainingStringKey = "signs.out-of-stock";
            }
            default -> {
                shopRemaining = 0;
                tradingStringKey = "MissingKey for shop type:" + shopType;
                noRemainingStringKey = "MissingKey for shop type:" + shopType;
            }
        }
        Component line2 = switch (shopRemaining) {
            //Unlimited
            case -1 ->
                    plugin.text().of(tradingStringKey, plugin.text().of("signs.unlimited").forLocale(locale.getLocale())).forLocale(locale.getLocale());
            //No remaining
            case 0 -> plugin.text().of(noRemainingStringKey).forLocale(locale.getLocale());
            //Has remaining
            default -> plugin.text().of(tradingStringKey, Component.text(shopRemaining)).forLocale(locale.getLocale());
        };
        lines.add(line2);

        //line 3
        if (plugin.getConfig().getBoolean("shop.force-use-item-original-name") || !this.getItem().hasItemMeta() || !this.getItem().getItemMeta().hasDisplayName()) {
            Component left = plugin.text().of("signs.item-left").forLocale();
            Component right = plugin.text().of("signs.item-right").forLocale();
            Component itemName = Util.getItemCustomName(getItem());
            Component itemComponents;
            if (itemName == null) {
                // We can't insert translatable components into a sign.
                if (PaperLib.isPaper()) {
                    itemComponents = plugin.getPlatform().getTranslation(getItem().getType());
                } else {
                    itemComponents = Component.text(CommonUtil.prettifyText(getItem().getType().name()));
                }
            } else {
                itemComponents = itemName;
            }
            lines.add(left.append(itemComponents).append(right));
        } else {
            lines.add(plugin.text().of("signs.item-left").forLocale().append(Util.getItemStackName(getItem()).append(plugin.text().of("signs.item-right").forLocale())));
        }

        //line 4
        Component line4;
        if (this.isStackingShop()) {
            line4 = plugin.text().of("signs.stack-price",
                    plugin.getShopManager().format(this.getPrice(), this),
                    item.getAmount(),
                    Util.getItemStackName(item)).forLocale(locale.getLocale());
        } else {
            line4 = plugin.text().of("signs.price", LegacyComponentSerializer.legacySection().deserialize(plugin.getShopManager().format(this.getPrice(), this))).forLocale(locale.getLocale());
        }
        lines.add(line4);

        return lines;
    }

    /**
     * Returns a list of signs that are attached to this shop (QuickShop and blank signs only)
     *
     * @return a list of signs that are attached to this shop (QuickShop and blank signs only)
     */
    @Override
    public @NotNull List<Sign> getSigns() {
        Util.ensureThread(false);
        List<Sign> signs = new ArrayList<>(4);
        if (this.getLocation().getWorld() == null) {
            return Collections.emptyList();
        }
        Block[] blocks = new Block[4];
        blocks[0] = location.getBlock().getRelative(BlockFace.EAST);
        blocks[1] = location.getBlock().getRelative(BlockFace.NORTH);
        blocks[2] = location.getBlock().getRelative(BlockFace.SOUTH);
        blocks[3] = location.getBlock().getRelative(BlockFace.WEST);
        for (Block b : blocks) {
            if (b == null) {
                continue;
            }
            BlockState state = PaperLib.getBlockState(b, false).getState();
            if (!(state instanceof Sign sign)) {
                continue;
            }
            if (isShopSign(sign)) {
                claimShopSign(sign);
                signs.add(sign);
            }
        }

        return signs;
    }

    /**
     * @return The list of players who can manage the shop.
     */
    @NotNull
    @Override
    @SuppressWarnings("removal")
    @Deprecated(forRemoval = true, since = "2.0.0.0")
    public List<UUID> getStaffs() {
        return ImmutableList.copyOf(playersCanAuthorize(BuiltInShopPermissionGroup.STAFF));
    }

    @Override
    @Nullable
    public UUID getTaxAccount() {
        UUID uuid = null;
        if (taxAccount != null) {
            uuid = taxAccount;
        } else {
            if (((SimpleShopManager) plugin.getShopManager()).getCacheTaxAccount() != null) {
                uuid = ((SimpleShopManager) plugin.getShopManager()).getCacheTaxAccount();
            }
        }
        ShopTaxAccountGettingEvent event = new ShopTaxAccountGettingEvent(this, uuid);
        event.callEvent();
        return event.getTaxAccount();

    }

    @Override
    public void setTaxAccount(@Nullable UUID taxAccount) {
        if (Objects.equals(taxAccount, this.taxAccount)) {
            return;
        }
        ShopTaxAccountChangeEvent event = new ShopTaxAccountChangeEvent(this, taxAccount);
        if (Util.fireCancellableEvent(event)) {
            return;
        }
        this.taxAccount = taxAccount;
        setDirty();
    }

    @Override
    @Nullable
    public UUID getTaxAccountActual() {
        return taxAccount;
    }

    @Override
    public boolean inventoryAvailable() {
        if (isUnlimited()) {
            return true;
        }
        if (isSelling()) {
            return getRemainingStock() > 0;
        }
        if (isBuying()) {
            return getRemainingSpace() > 0;
        }
        return true;
    }

    @Override
    public boolean isAttached(@NotNull Block b) {
        Util.ensureThread(false);
        return this.getLocation().getBlock().equals(Util.getAttached(b));
    }

    @Override
    public boolean isBuying() {
        return this.shopType == ShopType.BUYING;
    }

    @Override
    public boolean isDeleted() {
        return this.isDeleted;
    }

    @Override
    public boolean isDirty() {
        return this.dirty;
    }

    @Override
    public void setDirty(boolean isDirty) {
        this.dirty = isDirty;
    }

    @Override
    public boolean isDisableDisplay() {
        return disableDisplay;
    }

    @Override
    public void setDisableDisplay(boolean disabled) {
        if (this.disableDisplay == disabled) {
            return;
        }
        this.disableDisplay = disabled;
        setDirty();
        checkDisplay();
    }

    /**
     * Returns true if this shop is a double chest, and the other half is selling/buying the same as
     * this is buying/selling.
     *
     * @return true if this shop is a double chest, and the other half is selling/buying the same as
     * this is buying/selling.
     */
    @Override
    public boolean isDoubleShop() {
        Util.ensureThread(false);
        if (attachedShop == null) {
            return false;
        }
        if (attachedShop.matches(this.getItem())) {
            // They're both trading the same item
            // They're both buying or both selling => Not a double shop,
            // just two shops.
            // One is buying, one is selling.
            return this.getShopType() != attachedShop.getShopType();
        } else {
            return false;
        }
    }

    /**
     * Check if this shop is free shop
     *
     * @return Free Shop
     */
    @Override
    public boolean isFreeShop() {
        return this.price == 0.0d;
    }

    @Override
    public boolean isLeftShop() {
        return isLeftShop;
    }

    @Override
    public boolean isLoaded() {
        return this.isLoaded;
    }

    /**
     * Checks to see if it is a real double without updating anything.
     *
     * @return If the chest is a real double chest, as in it is a double and it has the same item.
     */
    @Override
    public boolean isRealDouble() {
        Util.ensureThread(false);
        if (attachedShop == null) {
            return false;
        }
        return attachedShop.matches(this.getItem());
    }

    @Override
    public boolean isSelling() {
        return this.shopType == ShopType.SELLING;
    }

    /**
     * Checks if a Sign is a ShopSign
     *
     * @param sign Target {@link Sign}
     * @return Is shop info sign
     */
    @Override
    public boolean isShopSign(@NotNull Sign sign) {
        // Check for new shop sign
        Component[] lines = new Component[sign.getLines().length];
        for (int i = 0; i < sign.getLines().length; i++) {
            lines[i] = plugin.getPlatform().getLine(sign, i);
        }
        // Can be claim

        boolean empty = true;
        for (Component line : lines) {
            if (!Util.isEmptyComponent(line)) {
                empty = false;
                break;
            }
        }

        if (empty) {
            return true;
        }

        // Check for exists shop sign (modern)
        ShopSignStorage shopSignStorage = sign.getPersistentDataContainer().get(SHOP_NAMESPACED_KEY, ShopSignPersistentDataType.INSTANCE);
        if (shopSignStorage == null) {
            // Try to read Reremake sign namespaced key
            shopSignStorage = sign.getPersistentDataContainer().get(LEGACY_SHOP_NAMESPACED_KEY, ShopSignPersistentDataType.INSTANCE);
        }
        if (shopSignStorage != null) {
            return shopSignStorage.equals(getLocation().getWorld().getName(), getLocation().getBlockX(), getLocation().getBlockY(), getLocation().getBlockZ());
        }
        return false;
    }

    /**
     * Gets shop status is stacking shop
     *
     * @return The shop stacking status
     */
    @Override
    public boolean isStackingShop() {
        return plugin.isAllowStack() && this.item.getAmount() > 1;
    }

    @Override
    public boolean isUnlimited() {
        return this.unlimited;
    }

    @Override
    public void setUnlimited(boolean unlimited) {
        if (this.unlimited == unlimited) {
            return;
        }
        Util.ensureThread(false);
        this.unlimited = unlimited;
        setDirty();
        this.setSignText();
    }

    /**
     * Check shop is or not still Valid.
     *
     * @return isValid
     */
    @Override
    public boolean isValid() {
        Util.ensureThread(false);
        if (this.isDeleted) {
            return false;
        }
        return Util.canBeShop(this.getLocation().getBlock());
    }

    /**
     * Returns true if the ItemStack matches what this shop is selling/buying
     *
     * @param item The ItemStack
     * @return True if the ItemStack is the same (Excludes amounts)
     */
    @Override
    public boolean matches(@Nullable ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemStack givenItem = item.clone();
        givenItem.setAmount(1);
        ItemStack shopItem = this.item.clone();
        shopItem.setAmount(1);
        return plugin.getItemMatcher().matches(shopItem, givenItem);
    }

    @Override
    public void onClick(@NotNull Player clicker) {
        Util.ensureThread(false);
        ShopClickEvent event = new ShopClickEvent(this, clicker);
        if (Util.fireCancellableEvent(event)) {
            Log.debug("Ignore shop click, because some plugin cancel it.");
            return;
        }
        refresh();
        setSignText(plugin.getTextManager().findRelativeLanguages(clicker));
    }

    /**
     * Load ContainerShop.
     */
    @Override
    public void onLoad() {
        Util.ensureThread(false);
        if (this.isLoaded) {
            Log.debug("Dupe load request, canceled.");
            return;
        }
        Map<Location, Shop> shopsInChunk = plugin.getShopManager().getShops(getLocation().getChunk());
        if (shopsInChunk == null || !shopsInChunk.containsValue(this)) {
            throw new IllegalStateException("Shop must register into ShopManager before loading.");
        }
        try (PerfMonitor ignored = new PerfMonitor("Shop Inventory Locate", Duration.of(1, ChronoUnit.SECONDS))) {
            inventoryWrapper = locateInventory(symbolLink);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load shop: " + symbolLink + ": " + e.getClass().getName() + ": " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug.delete-corrupt-shops")) {
                plugin.getLogger().warning("Deleting corrupt shop...");
                this.delete(false);
            } else {
                plugin.getLogger().warning("Unloading shops from memory, set `debug.delete-corrupt-shops` to true to delete corrupted shops.");
                this.delete(true);
            }
            return;
        }
        if (Util.fireCancellableEvent(new ShopLoadEvent(this))) {
            return;
        }
        this.isLoaded = true;
        plugin.getShopManager().getLoadedShops().add(this);
        //disable schedule check due to performance issue
        //plugin.getShopContainerWatcher().scheduleCheck(this);
        try (PerfMonitor ignored = new PerfMonitor("Shop Display Check", Duration.of(1, ChronoUnit.SECONDS))) {
            checkDisplay();
        }
    }

    /**
     * Unload ContainerShop.
     */
    @Override
    public void onUnload() {
        Util.ensureThread(false);
        if (!this.isLoaded) {
            Log.debug("Dupe unload request, canceled.");
            return;
        }
        if (inventoryPreview != null) {
            inventoryPreview.close();
        }
        if (this.displayItem != null) {
            this.displayItem.remove();
        }
        this.isLoaded = false;
        plugin.getShopManager().getLoadedShops().remove(this);
        new ShopUnloadEvent(this).callEvent();
    }

    @Override
    public void openPreview(@NotNull Player player) {
        if (inventoryPreview == null) {
            inventoryPreview = new InventoryPreview(plugin, getItem().clone(), player.getLocale());
        }
        inventoryPreview.show(player);

    }

    @Override
    public @NotNull Component ownerName(boolean forceUsername, @NotNull ProxiedLocale locale) {
        String playerName;
        if (plugin.getConfig().getBoolean("shops.async-owner-name-fetch", false)) {
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> plugin.getPlayerFinder().uuid2Name(owner), QuickExecutor.getCommonExecutor());
            try {
                playerName = future.get(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                playerName = "N/A";
            }
        } else {
            playerName = plugin.getPlayerFinder().uuid2Name(this.getOwner());
        }
        Component name;
        if (playerName == null) {
            name = plugin.text().of("unknown-owner").forLocale(locale.getLocale());
        } else {
            name = Component.text(playerName);
        }
        if (!forceUsername && isUnlimited()) {
            name = plugin.text().of("admin-shop").forLocale(locale.getLocale());
        }
        ShopOwnerNameGettingEvent event = new ShopOwnerNameGettingEvent(this, getOwner(), name);
        event.callEvent();
        name = event.getName();
        return name;
    }

    @Override
    public @NotNull Component ownerName(@NotNull ProxiedLocale locale) {
        return ownerName(false, locale);
    }

    @Override
    public @NotNull Component ownerName() {
        return ownerName(false, MsgUtil.getDefaultGameLanguageLocale());
    }

    /**
     * Check if player has permission to authorize the specified permission node.
     *
     * @param player     the player
     * @param namespace  the plugin instance for the permission node (namespace)
     * @param permission the permission node
     * @return true if player has permission, false otherwise
     */
    @Override
    public boolean playerAuthorize(@NotNull UUID player, @NotNull Plugin namespace, @NotNull String permission) {
        if (player.equals(getOwner())) {
            Log.permission("Check permission " + namespace.getName().toLowerCase(Locale.ROOT) + "." + permission + " for " + player + " -> " + "true");
            return true;
        }
        String group = getPlayerGroup(player);
        boolean r = plugin.getShopPermissionManager().hasPermission(group, namespace, permission);
        ShopAuthorizeCalculateEvent event = new ShopAuthorizeCalculateEvent(this, player, namespace, permission, r);
        event.callEvent();
        Log.permission("Check permission " + namespace.getName().toLowerCase(Locale.ROOT) + "." + permission + ": " + player + " -> " + event.getResult());
        return event.getResult();

    }

    /**
     * Check if player has permission to authorize the specified permission node.
     *
     * @param player     the player
     * @param permission the permission node
     * @return true if player has permission, false otherwise
     */
    @Override
    public boolean playerAuthorize(@NotNull UUID player, @NotNull BuiltInShopPermission permission) {
        return playerAuthorize(player, plugin, permission.getRawNode());
    }

    @Override
    public List<UUID> playersCanAuthorize(@NotNull BuiltInShopPermission permission) {
        return playersCanAuthorize(plugin, permission.getRawNode());
    }

    @Override
    public List<UUID> playersCanAuthorize(@NotNull BuiltInShopPermissionGroup permissionGroup) {
        return playerGroup.entrySet().stream().filter(entry -> entry.getValue().equals(permissionGroup.getNamespacedNode())).map(Map.Entry::getKey).toList();
    }

    @Override
    public List<UUID> playersCanAuthorize(@NotNull Plugin namespace, @NotNull String permission) {
        List<UUID> result = new ArrayList<>();
        for (Map.Entry<UUID, String> uuidStringEntry : this.playerGroup.entrySet()) {
            String group = uuidStringEntry.getValue();
            boolean r = plugin.getShopPermissionManager().hasPermission(group, namespace, permission);
            ShopAuthorizeCalculateEvent event = new ShopAuthorizeCalculateEvent(this, uuidStringEntry.getKey(), namespace, permission, r);
            event.callEvent();
            r = event.getResult();
            if (r) {
                result.add(uuidStringEntry.getKey());
            }
        }
        Log.permission("Check permission " + namespace.getName().toLowerCase(Locale.ROOT) + "." + permission + ": " + CommonUtil.list2String(result.stream().map(UUID::toString).toList()));
        return result;
    }

    @Override
    public void refresh() {
        Util.ensureThread(false);
        if (inventoryPreview != null) {
            inventoryPreview.close();
            inventoryPreview = null;
        }
        if (displayItem != null) {
            displayItem.remove();
        }

        if (plugin.isDisplayEnabled() && !isDisableDisplay()) {
            if (displayItem != null) {
                displayItem.remove();
            }
            // Update double shop status, is left status, and the attachedShop
            updateAttachedShop();
            // Update displayItem
            if (isDisplayItemChanged && !isDisableDisplay()) {
                initDisplayItem();
                isDisplayItemChanged = false;
            }
            //Update attachedShop DisplayItem
            if (attachedShop != null && attachedShop.isDisplayItemChanged) {
                attachedShop.refresh();
            }
            // Don't make an item for this chest if it's a left shop.
            if (!isLeftShop && !isDisableDisplay() && displayItem != null) {
                displayItem.spawn();
            }
        }
        setSignText();
    }

    /**
     * Removes an item from the shop.
     *
     * @param item   The itemstack. The amount does not matter, just everything else
     * @param amount The amount to remove from the shop.
     */
    @Override
    public void remove(@NotNull ItemStack item, int amount) {
        Util.ensureThread(false);
        if (this.unlimited) {
            return;
        }
        item = item.clone();
        int itemMaxStackSize = Util.getItemMaxStackSize(item.getType());
        InventoryWrapper inv = this.getInventory();
        if (inv == null) {
            plugin.getLogger().warning("Failed to process item remove, reason: " + item + " x" + amount + " to shop " + this + ": Inventory null.");
            return;
        }
        int remains = amount;
        while (remains > 0) {
            int stackSize = Math.min(remains, itemMaxStackSize);
            item.setAmount(stackSize);
            Objects.requireNonNull(inv).removeItem(item);
            remains -= stackSize;
        }
        this.setSignText();
    }

    @Override
    public @NotNull String saveExtraToYaml() {
        return extra.saveToString();
    }

    @Override
    public ShopInfoStorage saveToInfoStorage() {
        return new ShopInfoStorage(getLocation().getWorld().getName(), new BlockPos(getLocation()), getOwner(), getPrice(), Util.serialize(this.originalItem), isUnlimited() ? 1 : 0, getShopType().toID(), saveExtraToYaml(), getCurrency(), isDisableDisplay(), getTaxAccount(), inventoryWrapperProvider, saveToSymbolLink(), getPermissionAudiences());
    }

    @Override
    @NotNull
    public String saveToSymbolLink() {
        return symbolLink;
    }

    /**
     * Sells amount of item to Player p. Does NOT check our inventory, or balances
     *
     * @param seller          The seller
     * @param sellerInventory The seller's inventory
     * @param loc2Drop        Location to drop items if inventory are full
     * @param amount          The amount to sell
     */
    @Override
    public void sell(@NotNull UUID seller, @NotNull InventoryWrapper sellerInventory,
                     @NotNull Location loc2Drop, int amount) throws Exception {
        Util.ensureThread(false);
        amount = item.getAmount() * amount;
        if (amount < 0) {
            this.buy(seller, sellerInventory, loc2Drop, -amount);
            return;
        }
        // Items to drop on floor
        if (this.isUnlimited()) {
            SimpleInventoryTransaction transaction = SimpleInventoryTransaction
                    .builder()
                    .from(null)
                    .to(sellerInventory) // To void
                    .item(this.getItem())
                    .amount(amount)
                    .build();
            if (!transaction.failSafeCommit()) {
                if (plugin.getSentryErrorReporter() != null) {
                    plugin.getSentryErrorReporter().ignoreThrow();
                }
                throw new IllegalStateException("Failed to commit transaction! Economy Error Response:" + transaction.getLastError());
            }
        } else {
            InventoryWrapper chestInv = this.getInventory();
            if (chestInv == null) {
                plugin.getLogger().warning("Failed to process sell, reason: " + item + " x" + amount + " to shop " + this + ": Inventory null.");
                return;
            }
            SimpleInventoryTransaction transactionTake = SimpleInventoryTransaction
                    .builder()
                    .from(chestInv)
                    .to(sellerInventory) // To void
                    .item(this.getItem())
                    .amount(amount)
                    .build();
            if (!transactionTake.failSafeCommit()) {
                if (plugin.getSentryErrorReporter() != null) {
                    plugin.getSentryErrorReporter().ignoreThrow();
                }
                throw new IllegalStateException("Failed to commit transaction! Economy Error Response:" + transactionTake.getLastError());
            }
            this.setSignText(plugin.getTextManager().findRelativeLanguages(seller));
            if (attachedShop != null) {
                attachedShop.setSignText(plugin.getTextManager().findRelativeLanguages(seller));
            }
        }
    }

    @Override
    public void setDirty() {
        this.dirty = true;
    }

    /**
     * Save the extra data to the shop.
     *
     * @param plugin Plugin instace
     * @param data   The data table
     */
    @Override
    public void setExtra(@NotNull Plugin plugin, @NotNull ConfigurationSection data) {
        extra.set(plugin.getName(), data);
        setDirty();
    }

    @Override
    public void setInventory(@NotNull InventoryWrapper wrapper, @NotNull InventoryWrapperManager manager) {
        String provider = plugin.getInventoryWrapperRegistry().find(manager);
        if (provider == null) {
            throw new IllegalArgumentException("The manager " + manager.getClass().getName() + " not registered in registry.");
        }
        this.inventoryWrapper = wrapper;
        this.inventoryWrapperProvider = provider;
        this.symbolLink = manager.mklink(wrapper);
        setDirty();
        Log.debug("Inventory changed: " + this.symbolLink + ", wrapper provider:" + inventoryWrapperProvider);
        new ShopInventoryChangedEvent(wrapper, manager).callEvent();
    }

    @Override
    public void setPlayerGroup(@NotNull UUID player, @Nullable String group) {
        if (group == null) {
            group = BuiltInShopPermissionGroup.EVERYONE.getNamespacedNode();
        }
        new ShopPlayerGroupSetEvent(this, player, getPlayerGroup(player), group).callEvent();
        if (group.equals(BuiltInShopPermissionGroup.EVERYONE.getNamespacedNode())) {
            this.playerGroup.remove(player);
        } else {
            this.playerGroup.put(player, group);
        }
        setDirty();
    }

    @Override
    public void setPlayerGroup(@NotNull UUID player, @Nullable BuiltInShopPermissionGroup group) {
        if (group == null) {
            group = BuiltInShopPermissionGroup.EVERYONE;
        }
        new ShopPlayerGroupSetEvent(this, player, getPlayerGroup(player), group.getNamespacedNode()).callEvent();
        if (group == BuiltInShopPermissionGroup.EVERYONE) {
            this.playerGroup.remove(player);
        } else {
            setPlayerGroup(player, group.getNamespacedNode());
        }
        setDirty();
    }

    /**
     * Updates signs attached to the shop
     */
    @Override
    public void setSignText() {
        Util.ensureThread(false);
        if (!Util.isLoaded(this.location)) {
            return;
        }
        this.setSignText(getSignText(plugin.getTextManager().findRelativeLanguages(MsgUtil.getDefaultGameLanguageCode())));
    }

    /**
     * Changes all lines of text on a sign near the shop
     *
     * @param lines The array of lines to change. Index is line number.
     */
    @Override
    public void setSignText(@NotNull List<Component> lines) {
        Util.ensureThread(false);
        Log.debug("Globally sign text setting...");
        List<Sign> signs = this.getSigns();
        for (Sign sign : signs) {
            for (int i = 0; i < lines.size(); i++) {
                plugin.getPlatform().setLine(sign, i, lines.get(i));
            }
            if (plugin.getGameVersion().isSignTextDyeSupport()) {
                DyeColor dyeColor = Util.getDyeColor();
                if (dyeColor != null) {
                    sign.setColor(dyeColor);
                }
            }
            if (plugin.getGameVersion().isSignGlowingSupport()) {
                boolean isGlowing = plugin.getConfig().getBoolean("shop.sign-glowing");
                sign.setGlowingText(isGlowing);
            }
            sign.update(true);
            new ShopSignUpdateEvent(this, sign).callEvent();
        }
        if (plugin.getSignHooker() != null) {
            Log.debug("Start sign broadcast...");
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.getSignHooker().updatePerPlayerShopSignBroadcast(getLocation(), this), 2);
            Log.debug("Sign broadcast completed.");
        }
    }

    /**
     * Updates signs attached to the shop
     *
     * @param locale The locale to use for the sign text
     */
    @Override
    public void setSignText(@NotNull ProxiedLocale locale) {
        Util.ensureThread(false);
        if (!Util.isLoaded(this.location)) {
            return;
        }
        this.setSignText(getSignText(locale));
    }

    /**
     * Updates the shop into the database.
     */
    @Override
    @NotNull
    public CompletableFuture<Void> update() {
        // Warning! This method can be run in async thread.
        if (updating) {
            return CompletableFuture.completedFuture(null);
        }
        if (this.shopId == -1) {
            Log.debug("Skip shop database update because it not fully setup!");
            return CompletableFuture.completedFuture(null);
        }
        ShopUpdateEvent shopUpdateEvent = new ShopUpdateEvent(this);
        if (Util.fireCancellableEvent(shopUpdateEvent)) {
            Log.debug("The Shop update action was canceled by a plugin.");
            return CompletableFuture.completedFuture(null);
        }
        updating = true;
        return plugin.getDatabaseHelper().updateShop(this)
                .whenComplete((result, throwable) -> {
                    updating = false;
                    if (throwable == null) {
                        this.dirty = false;
                    } else {
                        plugin.getLogger().log(Level.WARNING,
                                "Could not update a shop in the database! Changes will revert after a reboot!", throwable);
                    }
                });
    }

    /**
     * Updates the attachedShop variable to reflect the currently attached shop, if any.
     * Also updates the left shop status.
     */
    @Override
    public void updateAttachedShop() {
        //TODO: Rewrite centering item feature, currently implement is buggy and mess
        Util.ensureThread(false);
        Block attachedChest = Util
                .getSecondHalf(this.getLocation().getBlock());
        Shop preValue = attachedShop;
        //Prevent chain chunk loading
        if (attachedChest == null || !Util.isLoaded(attachedChest.getLocation())) {
            attachedShop = null;
        } else {
            attachedShop = (ContainerShop) plugin.getShopManager().getShop(attachedChest.getLocation());
        }

        if (attachedShop != null && attachedShop.matches(this.getItem())) {
            updateLeftShop();
        } else {
            isLeftShop = false;
        }

        if (!Objects.equals(attachedShop, preValue)) {
            notifyDisplayItemChange();
        }
    }

    @Override
    public @NotNull Benefit getShopBenefit() {
        return this.benefit;
    }

    @Override
    public void setShopBenefit(@NotNull Benefit benefit) {
        this.benefit = benefit;
        setDirty();
    }

    /**
     * Check the container still there and we can keep use it.
     */
    public void checkContainer() {
        Util.ensureThread(false);
        if (!this.isLoaded) {
            return;
        }
        if (!Util.isLoaded(this.getLocation())) {
            return;
        }
        if (!Util.canBeShop(this.getLocation().getBlock())) {
            Log.debug("Shop at " + this.getLocation() + "@" + this.getLocation().getBlock()
                    + " container was missing, deleting...");
            plugin.logEvent(new ShopRemoveLog(CommonUtil.getNilUniqueId(), "Container invalid", saveToInfoStorage()));
            this.onUnload();
            this.delete(false);
        }
    }

    public @NotNull SimpleDataRecord createDataRecord() {
        return new SimpleDataRecord(
                getOwner(),
                Util.serialize(getItem()),
                getShopName(),
                getShopType().toID(),
                getCurrency(),
                getPrice(),
                isUnlimited(),
                isDisableDisplay(),
                getTaxAccount(),
                JsonUtil.getGson().toJson(getPermissionAudiences()),
                saveExtraToYaml(),
                getInventoryWrapperProvider(),
                saveToSymbolLink(),
                new Date(),
                getShopBenefit().serialize()
        );
    }

    /**
     * Returns the display item associated with this shop.
     *
     * @return The display item associated with this shop.
     */
    @Nullable
    public AbstractDisplayItem getDisplayItem() {
        return this.displayItem;
    }

    /**
     * @return The enchantments the shop has on its items.
     */
    public @NotNull Map<Enchantment, Integer> getEnchants() {
        if (this.item.hasItemMeta() && this.item.getItemMeta().hasEnchants()) {
            return Objects.requireNonNull(this.item.getItemMeta()).getEnchants();
        }
        return Collections.emptyMap();
    }

    /**
     * @return The ItemStack type of this shop
     */
    public @NotNull Material getMaterial() {
        return this.item.getType();
    }

    /**
     * Different with isDoubleShop, this method only check the shop is created on the double chest.
     *
     * @return true if create on double chest.
     */
    public boolean isDoubleChestShop() {
        Util.ensureThread(false);
        if (Util.isDoubleChest(this.getLocation().getBlock().getBlockData())) {
            return getAttachedShop() != null;
        }
        return false;
    }

    private @NotNull InventoryWrapper locateInventory(@Nullable String symbolLink) {
        if (symbolLink == null || symbolLink.isEmpty()) {
            throw new IllegalStateException("Symbol link is empty, that's not right bro.");
        }
        InventoryWrapperManager manager = plugin.getInventoryWrapperRegistry().get(getInventoryWrapperProvider());
        if (manager == null) {
            throw new IllegalStateException("Failed load shop data, the InventoryWrapper provider " + getInventoryWrapperProvider() + " invalid or failed to load!");
        }
        try {
            // this.symbolLink = manager.mklink(inventoryWrapper);
            return manager.locate(symbolLink);
        } catch (Exception e) {
            throw new IllegalStateException("Failed load shop data, the InventoryWrapper provider " + getInventoryWrapperProvider() + " returns error: " + e.getMessage(), e);
        }
    }

    private void notifyDisplayItemChange() {
        isDisplayItemChanged = true;
        if (attachedShop != null && !attachedShop.isDisplayItemChanged) {
            attachedShop.notifyDisplayItemChange();
        }
    }

    @Override
    public ReloadResult reloadModule() throws Exception {
        if (!plugin.isAllowStack()) {
            this.item.setAmount(1);
        } else {
            this.item.setAmount(this.originalItem.getAmount());
        }
        return Reloadable.super.reloadModule();
    }

    private ShopSignStorage saveToShopSignStorage() {
        return new ShopSignStorage(getLocation().getWorld().getName(), getLocation().getBlockX(), getLocation().getBlockY(), getLocation().getBlockZ());
    }

    @Override
    public String toString() {

        return "Shop " +
                (location.getWorld() == null ? "unloaded world" : location.getWorld().getName()) +
                "(" +
                location.getBlockX() +
                ", " +
                location.getBlockY() +
                ", " +
                location.getBlockZ() +
                ")" +
                " Owner: " + LegacyComponentSerializer.legacySection().serialize(this.ownerName(false, MsgUtil.getDefaultGameLanguageLocale())) + " - " + getOwner() +
                ", Unlimited: " + isUnlimited() +
                " Price: " + getPrice();
    }

    /**
     * This function calculates which block of a double chest is the left block,
     * relative to the direction the chest is facing. Left shops don't spawn items since
     * they merge items with the right shop.
     * It also updates the isLeftShop status of this class to reflect the changes.
     */
    private void updateLeftShop() {
        //TODO: Rewrite centering item feature, currently implement is buggy and mess
        if (attachedShop == null) {
            return;
        }
        boolean previousValue = isLeftShop;

        switch (((Chest) getLocation().getBlock().getBlockData()).getFacing()) {
            case WEST ->
                // left block has a smaller z value
                    isLeftShop = getLocation().getZ() < attachedShop.getLocation().getZ();
            case EAST ->
                // left block has a greater z value
                    isLeftShop = getLocation().getZ() > attachedShop.getLocation().getZ();
            case NORTH ->
                // left block has greater x value
                    isLeftShop = getLocation().getX() > attachedShop.getLocation().getX();
            case SOUTH ->
                // left block has a smaller x value
                    isLeftShop = getLocation().getX() < attachedShop.getLocation().getX();
            default -> isLeftShop = false;
        }
        if (isLeftShop != previousValue) {
            notifyDisplayItemChange();
        }
    }
}
