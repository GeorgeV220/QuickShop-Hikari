/*
 * This file is a part of project QuickShop, the name is ReflectFactory.java
 *  Copyright (C) PotatoCraft Studio and contributors
 *
 *  This program is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.maxgamer.quickshop.util;

import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;

/**
 * ReflectFactory is library builtin QuickShop to get/execute stuff that cannot be access with BukkitAPI with reflect way.
 *
 * @author Ghost_chu
 */
public class ReflectFactory {
    private static String cachedVersion = null;
    private static String nmsVersion;

    @NotNull
    public static String getNMSVersion() {
        if (nmsVersion == null) {
            String name = Bukkit.getServer().getClass().getPackage().getName();
            nmsVersion = name.substring(name.lastIndexOf('.') + 1);
        }
        return nmsVersion;
    }

    @NotNull
    public static String getServerVersion() {
        if (cachedVersion != null) {
            return cachedVersion;
        }
        try {
            Field consoleField = Bukkit.getServer().getClass().getDeclaredField("console");
            // protected
            consoleField.setAccessible(true);
            // dedicated server
            Object console = consoleField.get(Bukkit.getServer());
            cachedVersion = String.valueOf(
                    console.getClass().getSuperclass().getMethod("getVersion").invoke(console));
            return cachedVersion;
        } catch (Exception e) {
            //Fallback to common substring
            String[] strings = StringUtils.substringsBetween(Bukkit.getServer().getVersion(), "(MC: ", ")");
            if (strings != null && strings.length == 1) {
                cachedVersion = strings[0];
            } else {
                cachedVersion = "Unknown";
            }
            return cachedVersion;
        }
    }

}
