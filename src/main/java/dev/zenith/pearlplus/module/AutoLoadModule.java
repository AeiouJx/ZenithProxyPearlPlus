package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.event.chat.SystemChatEvent;
import com.zenith.event.chat.WhisperChatEvent;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.CACHE;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoLoadModule extends Module {
    // "U+1F4E8 <who> U+27A1 <message>", may be preceded by whatever else the server logged
    private static final Pattern SYSTEM_WHISPER_PATTERN = Pattern.compile(
            "^\\s*.*?\uD83D\uDCE8\uFE0F?\\s*(?<who>.*?)\\s*(?:\u27A1|\u2192|->)\\s*(?<msg>.+)$");
    // offline accounts are shown as "||Name", this also strips any [] / zero width decorations
    private static final Pattern NAME_DECORATION = Pattern.compile("[\\[\\]|\uFE0F\u200B-\u200D\u00A0]+");
    // assumed walking speed used for the arrival estimate
    private static final double WALK_BLOCKS_PER_SECOND = 4.3;

    private final PearlManager pearlManager = new PearlManager(this);

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoLoad.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
                of(WhisperChatEvent.class, this::onWhisper),
                of(SystemChatEvent.class, this::onSystemChat)
        );
    }

    private void onWhisper(WhisperChatEvent event) {
        if (!PLUGIN_CONFIG.autoLoad.enabled || event.outgoing()) return;

        String rawMessage = event.message().trim();
        String msg = rawMessage.toLowerCase();
        String[] lowerParts = msg.split("\\s+");
        String[] parts = rawMessage.trim().split("\\s+");
        var sender = event.sender();
        String name = sender.getName();
        UUID uuid = sender.getProfileId();

        String loadCommand = PLUGIN_CONFIG.autoLoad.loadCommand == null || PLUGIN_CONFIG.autoLoad.loadCommand.isBlank()
                ? "load" : PLUGIN_CONFIG.autoLoad.loadCommand.toLowerCase();

        // Check whitelist for load commands
        if (msg.startsWith(loadCommand)) {
            if (PLUGIN_CONFIG.autoLoad.whitelistEnabled && !PLUGIN_CONFIG.whitelist.containsKey(uuid)) {
                // Non-whitelisted player trying to load - ignore silently
                return;
            }
        }

        if (msg.equals("pearls")) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry != null && !playerEntry.pearls.isEmpty()) {
                String list = pearlManager.pearlsList(uuid);
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, list));
            }
            return;
        }

        if (lowerParts.length > 0 && "default".equals(lowerParts[0])) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry == null || playerEntry.pearls.isEmpty()) {
                info("Default request from player without pearls: " + name);
                return;
            }
            if (parts.length < 2) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Specify a pearl ID to set as default."));
                return;
            }
            String resolved = pearlManager.resolvePearlId(uuid, parts[1]);
            if (resolved == null) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Pearl not found."));
                return;
            }
            pearlManager.setDefaultPearl(uuid, resolved);
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Default pearl set to " + resolved + "."));
            return;
        }

        if (lowerParts.length > 0 && "rename".equals(lowerParts[0])) {
            var playerEntry = PLUGIN_CONFIG.players.get(uuid);
            if (playerEntry == null || playerEntry.pearls.isEmpty()) {
                info("Rename request from player without pearls: " + name);
                return;
            }
            if (parts.length < 3) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Usage: rename <oldId> <newId>"));
                return;
            }
            String oldPearlId = pearlManager.resolvePearlId(uuid, parts[1]);
            if (oldPearlId == null) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Pearl not found."));
                return;
            }
            String newPearlId = parts[2];
            boolean exists = playerEntry.pearls.keySet().stream()
                    .anyMatch(id -> id.equalsIgnoreCase(newPearlId));
            if (exists) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "A pearl with that id already exists."));
                return;
            }
            boolean renamed = pearlManager.renamePearl(uuid, oldPearlId, newPearlId);
            if (renamed) {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Renamed " + oldPearlId + " to " + newPearlId + "."));
            } else {
                sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Unable to rename pearl."));
            }
            return;
        }

        if (!msg.startsWith(loadCommand)) return;

        var playerEntry = PLUGIN_CONFIG.players.get(uuid);
        if (playerEntry == null || playerEntry.pearls.isEmpty()) {
            info("No pearls assigned to " + name);
            return;
        }

        String requestedPearl;

        if (!PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
            if (lowerParts.length > 2) {
                info("Extra arguments not allowed for " + name);
                return;
            }
        } else if (lowerParts.length > 3) {
            info("Too many arguments from " + name);
            return;
        }

        if (lowerParts.length == 1) {
            requestedPearl = pearlManager.defaultPearlId(uuid);
        } else {
            String candidate = parts[1];
            String resolved = pearlManager.resolvePearlId(uuid, candidate);
            if (resolved != null) {
                requestedPearl = resolved;
            } else if (PLUGIN_CONFIG.autoLoad.allowNoiseAfterPearl) {
                requestedPearl = pearlManager.defaultPearlId(uuid);
            } else {
                requestedPearl = null;
            }
        }

        if (requestedPearl == null || !playerEntry.pearls.containsKey(requestedPearl)) {
            info("Unauthorized load from " + name + " with arg: " + rawMessage);
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "No authorized pearls found."));
            return;
        }

        var pearl = playerEntry.pearls.get(requestedPearl);

        discordAndIngameNotification(com.zenith.discord.Embed.builder()
                .title("Recieved Whisper")
                .addField("Sender", name)
                .addField("Pearl", requestedPearl)
        );

        Double distance = distanceToPearl(pearl.x, pearl.y, pearl.z);
        int pullRange = PLUGIN_CONFIG.autoLoad.pullRange;
        if (distance != null && pullRange > 0 && distance > pullRange) {
            // deliberately no numbers in the reply, a measured distance would pin the spot down
            info(String.format("Out of pull range: %s asked for %s, %.0f blocks away (limit %d)",
                    name, requestedPearl, distance, pullRange));
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "That pearl is out of pull range."));
            return;
        }
        String travel = distance == null
                ? ""
                : String.format(" Distance: %.0f blocks, ETA ~%s.", distance, formatEta(distance));

        // Check the amount of pearls left for the user. Subtract the pearl being pulled only
        // when it is still sitting in its chamber.
        int presentPearls = pearlManager.countPresentPearls(uuid);
        int PearlsLeft = Math.max(0, presentPearls - (pearlManager.isPearlPresent(pearl) ? 1 : 0));

        // Set the default sentence to send.
        String pearlFeedback = "You have " + PearlsLeft + " pearls left.";

        if(PearlsLeft == 1){
            pearlFeedback = "Don't forget to drop down a new pearl, this is your last one!";
        }

        if (!pearlManager.isPearlPresent(pearl)) {
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "No pearl detected. Attempting to load anyways." + travel));
        }else{
            sendClientPacketAsync(ChatUtil.getWhisperChatPacket(name, "Loading pearl " + requestedPearl + "... "+pearlFeedback + travel));
        }

        pearlManager.loadPearl(pearl, name, uuid);

    }

    /** straight line distance from the bot to a stored pearl, null while not connected */
    private static Double distanceToPearl(int x, int y, int z) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return null;
        }
        var player = CACHE.getPlayerCache().getThePlayer();
        double dx = player.getX() - x;
        double dy = player.getY() - y;
        double dz = player.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String formatEta(double blocks) {
        long seconds = Math.max(1L, Math.round(blocks / WALK_BLOCKS_PER_SECOND));
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /**
     * 3C3U.org whispers do not match any chatschema, so they reach us as system chat:
     *   inbound  "U+1F4E8 ||BadApple U+27A1 tp"
     *   outbound "U+1F4E8 You U+27A1 ||BadApple zai"
     * Parses the line and forwards it to {@link #onWhisper} as a normal inbound whisper.
     */
    private void onSystemChat(SystemChatEvent event) {
        if (event.message() == null) return;
        Matcher matcher = SYSTEM_WHISPER_PATTERN.matcher(event.message().strip());
        if (!matcher.matches()) return;

        String rawWho = matcher.group("who").trim();
        if (rawWho.isEmpty()) return;
        String name = stripDecoration(rawWho);
        if (name.isEmpty()) return;

        // our own outbound whisper, shown with our own name instead of "You"
        var profile = CACHE.getProfileCache().getProfile();
        if (profile != null && name.equalsIgnoreCase(profile.getName())) return;

        PlayerListEntry sender = resolveSender(rawWho, name);
        if (sender == null) {
            // "You <arrow> ..." is our own outbound whisper and never resolves, stay quiet about it
            if (!"You".equalsIgnoreCase(rawWho)) {
                info("Ignored system whisper with unresolvable name: " + rawWho);
            }
            return;
        }

        onWhisper(new WhisperChatEvent(
                false,
                sender,
                selfEntry(),
                event.component(),
                matcher.group("msg")));
    }

    /**
     * Resolves the name of a system chat line to a tab list entry.
     * 3C3U shows offline accounts as "||Name" and premium accounts as "Name", and their
     * uuids are generated differently. Tab list first, then for a flagged offline account
     * that is not listed fall back to the vanilla offline uuid "OfflinePlayer:Name",
     * which is the same uuid the server (and autodetect) assigns to it.
     */
    private static PlayerListEntry resolveSender(String rawWho, String name) {
        var tab = CACHE.getTabListCache();
        var entry = tab.getFromName(name)
                .or(() -> tab.getFromName(rawWho))
                .or(() -> tab.getRecentlyRemovedPlayer(name))
                .or(() -> tab.getEntries().stream()
                        .filter(e -> e.getName().equalsIgnoreCase(name))
                        .findFirst());
        if (entry.isPresent()) return entry.get();

        // premium accounts have no offline uuid we could derive, their uuid only exists in the tab list
        if (rawWho.indexOf('|') < 0) return null;
        if (!ChatUtil.isValidPlayerName(name)) return null;
        // 3C3U only shows "||" as a display marker: the tab entry is "||BadApple" but its uuid is
        // OfflinePlayer:BadApple, the same one autodetect recorded for the thrown pearl.
        // The entry keeps the raw name so whisper replies keep targeting "||BadApple".
        UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new PlayerListEntry(rawWho, offlineUuid);
    }

    private static PlayerListEntry selfEntry() {
        var profile = CACHE.getProfileCache().getProfile();
        if (profile == null) return null;
        // the discord chat relay reads event.receiver().getName(), a missing tab entry
        // must not leave the receiver null
        return CACHE.getTabListCache().get(profile.getId())
                .orElseGet(() -> new PlayerListEntry(profile.getName(), profile.getId()));
    }

    private static String stripDecoration(String name) {
        return NAME_DECORATION.matcher(name).replaceAll("").trim();
    }
}
