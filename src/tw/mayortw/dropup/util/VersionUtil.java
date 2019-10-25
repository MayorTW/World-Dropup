package tw.mayortw.dropup.util;

import org.bukkit.Bukkit;

public class VersionUtil {
    /*
     * Check if server is at least ver version
     */
    public static boolean atLeast(String verString) {
        String[] compVer = verString.split("\\.");
        String[] servVer = Bukkit.getBukkitVersion().split("\\.");

        for(int i = 0; i < compVer.length; i++) {
            if(i >= servVer.length) return false;

            int compNum = 0;
            int servNum = 0;
            try {
                compNum = Integer.parseInt(compVer[i]);
                servNum = Integer.parseInt(servVer[i]);
            } catch (NumberFormatException e) {}

            if(compNum != servNum) return compNum < servNum;
        }

        return true;
    }
}

