package me.ehsan.morghchat.interactive;

import me.ehsan.morghchat.MorghChat;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/** Keeps InteractiveChat-style inventory snapshots strictly read-only. */
public final class InteractiveChatListener implements Listener {
    private final MorghChat plugin;

    public InteractiveChatListener(MorghChat plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (plugin.getInteractiveChatManager().isReadOnly(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (plugin.getInteractiveChatManager().isReadOnly(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }
}
