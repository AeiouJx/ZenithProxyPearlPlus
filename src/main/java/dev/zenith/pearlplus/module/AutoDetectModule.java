package dev.zenith.pearlplus.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.module.api.Module;
import com.zenith.util.ChatUtil;
import dev.zenith.pearlplus.PearlPlusConfig;
import org.geysermc.mcprotocollib.protocol.data.game.PlayerListEntry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.ProjectileData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.*;

import static com.zenith.Globals.*;
import static com.github.rfresh2.EventConsumer.of;
import static dev.zenith.pearlplus.PearlPlusPlugin.PLUGIN_CONFIG;

public class AutoDetectModule extends Module {
    private static final long STABLE_LOCATION_DURATION_MS = 3_000L;
    private static final long STORED_PEARL_REMOVAL_GRACE_MS = 60_000L;
    private static final int POSITION_HISTORY_LIMIT = 8;
    // a stasis chamber is identified by its column, but several chambers can be stacked
    // vertically in the same column, typically more than 8 blocks apart. the trapdoor of a
    // chamber sits one block above or below its pearl, so this only has to absorb the
    // bounce of a pearl inside the same chamber and can never reach the neighbouring one.
    static final int COLUMN_Y_TOLERANCE = 4;
    // the server kicks for spamming when a burst of whispers arrives in one tick, so
    // registration/merge/removal notices are queued and drained one message at a time
    private static final long WHISPER_MIN_INTERVAL_MS = 2_000L;
    // a burst of registrations (a pp clear, or the first scan after a reconnect) is held
    // back until detection goes quiet, then announced as a single whisper per owner
    private static final long REGISTRATION_BATCH_QUIET_MS = 1_500L;
    private static final long REGISTRATION_BATCH_MAX_MS = 15_000L;

    private final Map<Integer, TrackedPearl> trackedPearls = new HashMap<>();
    private final PearlManager pearlManager = new PearlManager(this);
    // a chamber is shared: several players may throw into the same column and each of
    // them keeps their own entry, so every id below is scoped to its owner instead of
    // to the position. a loader at the edge of view distance flickers in and out while
    // the bot walks, so a chamber being seen again is not news: every pearl id is
    // announced exactly once and kept here until its config entry goes away, which also
    // stops the whisper flood that gets the account muted for spamming
    private final Set<String> announcedPearlIds = new HashSet<>();
    private boolean pendingReconnectGrace = false;
    private long suppressStoredPearlRemovalUntil = 0L;
    private final Deque<QueuedWhisper> whisperQueue = new ArrayDeque<>();
    private long nextWhisperAtMs = 0L;
    private final Map<String, List<String>> pendingRegistrations = new LinkedHashMap<>();
    private long registrationBatchStartedAtMs = 0L;
    private long registrationLastAtMs = 0L;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.autoDetect.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
                of(ClientBotTick.class, event -> {
                    flushRegistrationNotices();
                    flushWhispers();
                    if (!PLUGIN_CONFIG.autoDetect.enabled) {
                        return;
                    }
                    scanForPearls();
                }),
                of(ClientBotTick.Stopped.class, event -> {
                    trackedPearls.clear();
                    pendingReconnectGrace = true;
                })
        );
    }

    @Override
    public void onEnable() {
        extendStoredPearlRemovalGracePeriod();
        markExistingPearls();
    }

    @Override
    public void onDisable() {
        trackedPearls.clear();
        pendingReconnectGrace = false;
        announcedPearlIds.clear();
        PearlManager.flushPendingConfigSave();
    }

    public boolean isTemporaryModeEnabled() {
        return PLUGIN_CONFIG.autoDetect.temporaryMode;
    }

    public void onTemporaryModeToggle(boolean enabled) {
        info("PearlPlus Detect temp loader removal " + (enabled ? "enabled" : "disabled"));
    }

    public void markExistingPearls() {
        announcedPearlIds.clear();
        announcedPearlIds.addAll(configuredPearlIds());
        var cache = CACHE.getEntityCache();
        trackedPearls.clear();
        if (cache == null) {
            return;
        }

        Map<Integer, Entity> entities = cache.getEntities();
        long now = System.currentTimeMillis();

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.ENDER_PEARL) {
                continue;
            }

            BlockPosition position = blockPositionOf(entity);
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);
            // the chamber is exclusive: whatever is registered here is stamped onto the
            // pearl sitting in it, so a foreign pearl carries the occupant's id and is
            // turned away at registration instead of taking the slot
            StoredPearlEntry storedEntry = findStoredPearlInChamber(position, resolvedOwner).orElse(null);
            OwnerInfo owner = selectOwner(resolvedOwner, storedEntry != null ? storedEntry.ownerInfo() : null, null);

            TrackedPearl tracked = new TrackedPearl(position, owner, now);
            tracked.setPearlId(storedEntry != null ? storedEntry.pearl().pearlId : null);
            trackedPearls.put(entity.getEntityId(), tracked);
        }
    }

    private void scanForPearls() {
        long now = System.currentTimeMillis();
        announcedPearlIds.retainAll(configuredPearlIds());

        if (pendingReconnectGrace) {
            extendStoredPearlRemovalGracePeriod();
            pendingReconnectGrace = false;
        }

        var entityCache = CACHE.getEntityCache();
        if (entityCache == null) {
            trackedPearls.clear();
            return;
        }

        Map<Integer, Entity> entities = entityCache.getEntities();

        trackedPearls.entrySet().removeIf(entry -> {
            Entity entity = entities.get(entry.getKey());
            if (entity == null || entity.getEntityType() != EntityType.ENDER_PEARL) {
                handlePearlRemoval(entry.getValue());
                return true;
            }
            return false;
        });

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.ENDER_PEARL) {
                continue;
            }

            int entityId = entity.getEntityId();
            BlockPosition position = blockPositionOf(entity);
            OwnerInfo resolvedOwner = resolveOwnerInfo(entity, entities).orElse(null);

            TrackedPearl tracked = trackedPearls.get(entityId);
            if (tracked == null) {
                StoredPearlEntry storedEntry = findStoredPearlInChamber(position, resolvedOwner).orElse(null);
                OwnerInfo owner = selectOwner(resolvedOwner, storedEntry != null ? storedEntry.ownerInfo() : null, null);
                tracked = new TrackedPearl(position, owner, now);
                tracked.setPearlId(storedEntry != null ? storedEntry.pearl().pearlId : null);
                trackedPearls.put(entityId, tracked);

                info(String.format(
                        "Detected new ender pearl (id=%d) at (%.2f, %.2f, %.2f) [block %d %d %d] thrown by %s",
                        entityId,
                        entity.getX(),
                        entity.getY(),
                        entity.getZ(),
                        position.x(),
                        position.y(),
                        position.z(),
                        formatOwner(owner)
                ));
            } else {
                tracked.updatePosition(position, now);
                OwnerInfo previousOwner = tracked.owner();
                StoredPearlEntry storedEntry = findStoredPearlInChamber(position, selectOwner(resolvedOwner, previousOwner, null)).orElse(null);
                OwnerInfo owner = selectOwner(resolvedOwner, storedEntry != null ? storedEntry.ownerInfo() : null, previousOwner);
                tracked.setOwner(owner);
                if (storedEntry != null && storedEntry.pearl().pearlId != null) {
                    tracked.setPearlId(storedEntry.pearl().pearlId);
                }
            }
        }

        attemptAutoRegistration(now);
        checkStoredPearlsForMissingEntities(entities);
    }

    private void handlePearlRemoval(TrackedPearl trackedPearl) {
        info(String.format(
                "Ender pearl at block %d %d %d thrown by %s broke or despawned",
                trackedPearl.blockX(),
                trackedPearl.blockY(),
                trackedPearl.blockZ(),
                trackedPearl.ownerSummary()
        ));
        handleTemporaryRemoval(trackedPearl);
    }

    private void handleTemporaryRemoval(TrackedPearl trackedPearl) {
        if (!isTemporaryModeEnabled()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
        // only the entry of the pearl's own owner is released: the chamber may hold
        // other players' pearls at the same time and those keep their own registration
        Optional<StoredPearlEntry> storedEntry = resolveStoredEntry(trackedPearl.registrationPosition(), trackedPearl.owner());
        if (storedEntry.isEmpty()) {
            return;
        }
        trackedPearl.setOwner(selectOwner(trackedPearl.owner(), storedEntry.get().ownerInfo(), trackedPearl.owner()));
        trackedPearl.setPearlId(storedEntry.get().pearl().pearlId);
        if (trackedPearl.pearlId() == null || trackedPearl.owner() == null || trackedPearl.owner().uuid() == null) {
            return;
        }

        if (!withinRemovalRange(trackedPearl.registrationPosition())) {
            return;
        }

        sendRemovalWarning(trackedPearl);
        sendUnregisterWhisper(trackedPearl.owner(), trackedPearl.pearlId());
        pearlManager.removePearl(storedEntry.get().ownerUuid(), trackedPearl.pearlId());
    }

    private void attemptAutoRegistration(long now) {
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (!tracked.ownerHasName() || tracked.owner().uuid() == null) {
                if (!tracked.waitingForNameLogged()) {
                    info("Waiting for thrower's name/uuid before auto-registering loader");
                    tracked.markWaitingForNameLogged();
                }
                continue;
            } else {
                tracked.clearWaitingForNameLog();
            }

            if (!tracked.isStable(now)) {
                continue;
            }

            BlockPosition target = tracked.registrationPosition();
            // exclusive chambers: whoever registered first keeps the spot, a pearl thrown
            // by somebody else never takes it over, it is only released once the pearl
            // registered here is gone (temporary mode)
            Optional<StoredPearlEntry> existingStored = findStoredPearlInChamber(target, tracked.owner());
            if (existingStored.isPresent()) {
                StoredPearlEntry storedPearl = existingStored.get();
                if (isDifferentOwner(tracked.owner(), storedPearl.ownerInfo())) {
                    if (!tracked.conflictNotified()) {
                        sendForeignOwnershipWhisper(tracked.owner(), storedPearl);
                        tracked.markConflictNotified();
                    }
                    continue;
                }

                tracked.setPearlId(storedPearl.pearl().pearlId);
                tracked.setOwner(selectOwner(tracked.owner(), storedPearl.ownerInfo(), tracked.owner()));

                if (storedPearl.pearl().pearlId != null
                        && tracked.owner() != null
                        && tracked.owner().uuid() != null) {
                    pearlManager.recordPearl(
                            tracked.owner().uuid(),
                            tracked.owner().name(),
                            storedPearl.pearl().pearlId,
                            target.x(),
                            target.y(),
                            target.z());

                    if (!tracked.registrationNotified()) {
                        tracked.markRegistrationNotified();
                        String mergedId = storedPearl.pearl().pearlId;
                        // seen before (or still stored): the owner already knows this loader,
                        // so only a pearl id announced for the very first time is reported
                        if (mergedId != null
                                && announcedPearlIds.add(ownerScopedId(storedPearl.ownerUuid(), storedPearl.ownerName(), mergedId))) {
                            sendPearlMergedNotification(mergedId, target, tracked);
                        }
                    }
                }

                continue;
            }

            String pearlId = tracked.pearlId();
            if (pearlId == null || pearlId.isBlank()) {
                pearlId = pearlManager.nextAvailablePearlId(tracked.owner().uuid(), tracked.owner().name());
                if (pearlId == null) {
                    continue;
                }
                tracked.setPearlId(pearlId);
            }

            info(String.format(
                    "Registering pearl %s at %d %d %d for %s",
                    pearlId,
                    target.x(),
                    target.y(),
                    target.z(),
                    tracked.ownerSummary()
            ));

            pearlManager.recordPearl(tracked.owner().uuid(), tracked.owner().name(), pearlId, target.x(), target.y(), target.z());

            String ownerName = tracked.owner().name();
            // a column that is not in the config yet is a brand new discovery (a pp clear,
            // or a loader the plugin has never seen), so it is always announced
            if (!ownerName.isBlank() && queueRegistrationNotice(tracked.owner().uuid(), ownerName, pearlId)) {
                tracked.markRegistrationNotified();
            }
            sendRegistrationNotification(pearlId, target, tracked);
        }
    }

    private boolean queueRegistrationNotice(UUID ownerUuid, String ownerName, String pearlId) {
        if (ownerName == null || ownerName.isBlank() || pearlId == null || pearlId.isBlank()) {
            return false;
        }

        int batched;
        synchronized (pendingRegistrations) {
            announcedPearlIds.add(ownerScopedId(ownerUuid, ownerName, pearlId));
            List<String> pearlIds = pendingRegistrations.computeIfAbsent(ownerName, key -> new ArrayList<>());
            if (pearlIds.contains(pearlId)) {
                return true;
            }
            pearlIds.add(pearlId);
            long now = System.currentTimeMillis();
            if (registrationBatchStartedAtMs == 0L) {
                registrationBatchStartedAtMs = now;
            }
            registrationLastAtMs = now;
            batched = pearlIds.size();
        }
        info(String.format(
                "Queued registration notice to %s for loader %s (batched=%d)",
                ownerName,
                pearlId,
                batched
        ));
        return true;
    }

    /**
     * Turns every owner that has been waiting through a detection burst into a single
     * summary whisper, but only once no further pearl showed up for a quiet period.
     */
    private void flushRegistrationNotices() {
        Map<String, List<String>> batch;
        synchronized (pendingRegistrations) {
            if (pendingRegistrations.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            boolean quiet = now - registrationLastAtMs >= REGISTRATION_BATCH_QUIET_MS;
            boolean maxed = now - registrationBatchStartedAtMs >= REGISTRATION_BATCH_MAX_MS;
            if (!quiet && !maxed) {
                return;
            }
            batch = new LinkedHashMap<>(pendingRegistrations);
            pendingRegistrations.clear();
            registrationBatchStartedAtMs = 0L;
            registrationLastAtMs = 0L;
        }

        for (Map.Entry<String, List<String>> entry : batch.entrySet()) {
            queueWhisper(entry.getKey(), registrationNoticeMessage(entry.getValue()));
        }
        info(String.format(
                "Flushed %d registration notice(s) into the whisper queue",
                batch.values().stream().mapToInt(List::size).sum()
        ));
    }

    private String registrationNoticeMessage(List<String> pearlIds) {
        String loadCommand = PLUGIN_CONFIG.autoLoad.loadCommand == null || PLUGIN_CONFIG.autoLoad.loadCommand.isBlank()
                ? "load" : PLUGIN_CONFIG.autoLoad.loadCommand;
        Optional<String> botName = determineBotName();

        if (pearlIds.size() == 1) {
            String pearlId = pearlIds.get(0);
            return botName
                    .map(name -> String.format("Pearl Registered. Load me with /w %s %s %s", name, loadCommand, pearlId))
                    .orElse(String.format("Pearl Registered as %s.", pearlId));
        }

        String joined = String.join(", ", pearlIds);
        return botName
                .map(name -> String.format("Pearl Registered (%d): %s. Load with /w %s %s <id>",
                        pearlIds.size(), joined, name, loadCommand))
                .orElse(String.format("Pearl Registered (%d): %s.", pearlIds.size(), joined));
    }

    private void queueWhisper(String target, String message) {
        if (target == null || target.isBlank() || message == null || message.isBlank()) {
            return;
        }
        synchronized (whisperQueue) {
            for (QueuedWhisper queued : whisperQueue) {
                if (queued.target().equals(target) && queued.message().equals(message)) {
                    return;
                }
            }
            whisperQueue.add(new QueuedWhisper(target, message));
        }
    }

    private void flushWhispers() {
        QueuedWhisper pending;
        synchronized (whisperQueue) {
            if (whisperQueue.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < nextWhisperAtMs) {
                return;
            }
            pending = whisperQueue.poll();
            nextWhisperAtMs = now + WHISPER_MIN_INTERVAL_MS;
        }
        sendClientPacketAsync(ChatUtil.getWhisperChatPacket(pending.target(), pending.message()));
    }

    /**
     * Turns a pearl of another player away from a chamber that is already registered,
     * naming both the occupant and the loader so it is clear who has to be asked.
     */
    private void sendForeignOwnershipWhisper(OwnerInfo thrower, StoredPearlEntry stored) {
        if (thrower == null || !thrower.hasName() || stored == null) {
            return;
        }
        String occupant = stored.ownerInfo().hasName() ? stored.ownerInfo().name() : "another player";
        String message = String.format("Pearl spot already belongs to %s (%s).", occupant, stored.pearl().pearlId);
        queueWhisper(thrower.name(), message);
        info(String.format("Queued whisper to %s that chamber is taken by %s", thrower.name(), stored.ownerInfo().describe()));
    }

    private void sendRegistrationNotification(String pearlId, BlockPosition position, TrackedPearl trackedPearl) {
        if (pearlId == null || pearlId.isBlank() || position == null) {
            return;
        }

        String ownerSummary = trackedPearl != null ? trackedPearl.ownerSummary() : "unknown";

        discordAndIngameNotification(Embed.builder()
                .title("Pearl Registered")
                .addField("Pearl", pearlId)
                .addField("Owner", ownerSummary)
                .addField("Position", String.format("||%d %d %d||", position.x(), position.y(), position.z()))
        );
    }

    private void sendPearlMergedNotification(String pearlId, BlockPosition position, TrackedPearl trackedPearl) {
        if (pearlId == null || pearlId.isBlank() || position == null) {
            return;
        }

        info(String.format(
                "Pearl at %d %d %d merged into existing loader %s for %s",
                position.x(),
                position.y(),
                position.z(),
                pearlId,
                trackedPearl != null ? trackedPearl.ownerSummary() : "unknown"
        ));

        String ownerName = trackedPearl != null && trackedPearl.owner() != null
                ? trackedPearl.owner().name()
                : null;
        UUID ownerUuid = trackedPearl != null && trackedPearl.owner() != null
                ? trackedPearl.owner().uuid()
                : null;
        // this path already sends its own "Pearl Merged" embed, only the whisper is batched
        if (ownerName != null && !ownerName.isBlank()) {
            queueRegistrationNotice(ownerUuid, ownerName, pearlId);
        }

        discordAndIngameNotification(Embed.builder()
                .title("Pearl Merged")
                .addField("Pearl", pearlId)
                .addField("Owner", trackedPearl != null ? trackedPearl.ownerSummary() : "unknown")
                .addField("Position", String.format("||%d %d %d||", position.x(), position.y(), position.z()))
        );
    }

    /**
     * A stored pearl counts as still there only while its own pearl sits in the same
     * chamber. Pearls of other players in a shared chamber, or pearls whose thrower
     * cannot be identified, are handled separately: an unattributable pearl is treated
     * as still present because it may be the registered one.
     */
    private boolean isStoredPearlStillThere(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
        for (TrackedPearl tracked : trackedPearls.values()) {
            if (!withinChamber(tracked.registrationPosition(), pearl)) {
                continue;
            }
            OwnerInfo owner = tracked.owner();
            if (owner == null || (owner.uuid() == null && !owner.hasName())) {
                return true;
            }
            if (sameOwner(owner, new OwnerInfo(ownerUuid, ownerName))) {
                return true;
            }
        }
        return false;
    }

    private boolean withinChamber(BlockPosition position, PearlPlusConfig.StoredPearl pearl) {
        return position != null
                && pearl != null
                // one block of slack: a pearl resting on a block boundary, or a bounce
                // peak reported as the registration position, lands next to its block
                && Math.abs(position.x() - pearl.x) <= 1
                && Math.abs(position.z() - pearl.z) <= 1
                && Math.abs(position.y() - pearl.y) <= COLUMN_Y_TOLERANCE;
    }

    private boolean sameOwner(OwnerInfo first, OwnerInfo second) {
        if (first == null || second == null) {
            return false;
        }
        if (first.uuid() != null && second.uuid() != null) {
            return first.uuid().equals(second.uuid());
        }
        if (first.hasName() && second.hasName()) {
            return first.name().equalsIgnoreCase(second.name());
        }
        return false;
    }

    private Optional<String> determineBotName() {
        if (CACHE == null) {
            return Optional.empty();
        }
        var profileCache = CACHE.getProfileCache();
        if (profileCache == null) {
            return Optional.empty();
        }
        var profile = profileCache.getProfile();
        if (profile == null) {
            return Optional.empty();
        }
        String name = profile.getName();
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    private Optional<UUID> determineBotUuid() {
        if (CACHE == null) {
            return Optional.empty();
        }
        var profileCache = CACHE.getProfileCache();
        if (profileCache == null) {
            return Optional.empty();
        }
        var profile = profileCache.getProfile();
        if (profile == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(profile.getId());
    }

    private String formatOwner(OwnerInfo owner) {
        return owner != null ? owner.describe() : "unknown";
    }

    private OwnerInfo selectOwner(OwnerInfo primary, OwnerInfo secondary, OwnerInfo fallback) {
        return mergeOwnerInfo(primary, mergeOwnerInfo(secondary, fallback));
    }

    private OwnerInfo mergeOwnerInfo(OwnerInfo preferred, OwnerInfo fallback) {
        if (preferred == null) {
            return fallback;
        }
        if (fallback == null) {
            return preferred;
        }
        UUID uuid = preferred.uuid() != null ? preferred.uuid() : fallback.uuid();
        String name = preferred.hasName() ? preferred.name() : fallback.name();
        return new OwnerInfo(uuid, name);
    }

    private boolean isDifferentOwner(OwnerInfo thrower, OwnerInfo storedOwner) {
        if (thrower == null || storedOwner == null) {
            return false;
        }
        if (thrower.uuid() != null && storedOwner.uuid() != null) {
            return !thrower.uuid().equals(storedOwner.uuid());
        }
        if (thrower.hasName() && storedOwner.hasName()) {
            return !thrower.name().equalsIgnoreCase(storedOwner.name());
        }
        return false;
    }

    private Optional<OwnerInfo> resolveOwnerInfo(Entity pearl, Map<Integer, Entity> entities) {
        Optional<OwnerInfo> resolved = resolveOwnerFromProjectileOwner(pearl, entities);
        if (resolved.isPresent() || !PLUGIN_CONFIG.autoDetect.distanceCheck) {
            return resolved;
        }
        return resolveOwnerFromClosestPlayer(pearl, entities);
    }

    private Optional<OwnerInfo> resolveOwnerFromProjectileOwner(Entity pearl, Map<Integer, Entity> entities) {
        var data = pearl.getObjectData();
        if (!(data instanceof ProjectileData projectileData)) {
            return Optional.empty();
        }

        int ownerEntityId = projectileData.getOwnerId();
        if (ownerEntityId <= 0) {
            return Optional.empty();
        }

        Entity ownerEntity = entities.get(ownerEntityId);
        UUID ownerUuid = ownerEntity != null ? ownerEntity.getUuid() : null;
        String ownerName = ownerUuid != null ? resolveOwnerName(ownerUuid).orElse(null) : null;

        if (ownerUuid == null && ownerName == null) {
            return Optional.empty();
        }
        return Optional.of(new OwnerInfo(ownerUuid, ownerName));
    }

    private Optional<OwnerInfo> resolveOwnerFromClosestPlayer(Entity pearl, Map<Integer, Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return Optional.empty();
        }

        Optional<UUID> botUuid = determineBotUuid();
        Entity closest = null;
        double closestDistanceSq = 2.0;
        double pearlX = pearl.getX();
        double pearlY = pearl.getY();
        double pearlZ = pearl.getZ();

        for (Entity entity : entities.values()) {
            if (entity.getEntityType() != EntityType.PLAYER) {
                continue;
            }
            UUID candidateUuid = entity.getUuid();
            if (botUuid.isPresent() && botUuid.get().equals(candidateUuid)) {
                continue;
            }

            double dx = entity.getX() - pearlX;
            double dy = entity.getY() - pearlY;
            double dz = entity.getZ() - pearlZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq < closestDistanceSq) {
                closestDistanceSq = distanceSq;
                closest = entity;
            }
        }

        if (closest == null) {
            return Optional.empty();
        }

        UUID ownerUuid = closest.getUuid();
        String ownerName = ownerUuid != null ? resolveOwnerName(ownerUuid).orElse(null) : null;

        if (ownerUuid == null && ownerName == null) {
            return Optional.empty();
        }
        return Optional.of(new OwnerInfo(ownerUuid, ownerName));
    }

    private Optional<String> resolveOwnerName(UUID ownerUuid) {
        if (ownerUuid == null || CACHE == null) {
            return Optional.empty();
        }
        return CACHE.getTabListCache()
                .get(ownerUuid)
                .map(PlayerListEntry::getName)
                .filter(name -> !name.isBlank());
    }

    private BlockPosition blockPositionOf(Entity entity) {
        return new BlockPosition(
                (int) Math.floor(entity.getX()),
                (int) Math.round(entity.getY()), // Replaced floor with round, since in rare cases the pearl Y location was wrong.
                (int) Math.floor(entity.getZ())
        );
    }

    private void extendStoredPearlRemovalGracePeriod() {
        suppressStoredPearlRemovalUntil = System.currentTimeMillis() + STORED_PEARL_REMOVAL_GRACE_MS;
    }

    private void checkStoredPearlsForMissingEntities(Map<Integer, Entity> entities) {
        if (!isTemporaryModeEnabled()) {
            return;
        }
        if (PLUGIN_CONFIG.players.isEmpty()) {
            return;
        }
        Proxy proxy = Proxy.getInstance();
        if (proxy == null || !proxy.isConnected() || proxy.isInQueue()) {
            return;
        }
        if (System.currentTimeMillis() < suppressStoredPearlRemovalUntil) {
            return;
        }

        for (var entry : new HashMap<>(PLUGIN_CONFIG.players).entrySet()) {
            UUID ownerUuid = entry.getKey();
            var playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null) {
                continue;
            }
            for (var pearlEntry : new HashMap<>(playerPearls.pearls).entrySet()) {
                PearlPlusConfig.StoredPearl stored = pearlEntry.getValue();
                if (stored == null) {
                    continue;
                }
                // only this owner's own pearl keeps its entry alive: in a shared chamber
                // the pearls of the other players do not hold a stale registration here
                if (isStoredPearlStillThere(ownerUuid, playerPearls.playerName, stored)) {
                    continue;
                }
                if (!withinRemovalRange(new BlockPosition(stored.x, stored.y, stored.z))) {
                    continue;
                }

                var trackedPearl = new TrackedPearl(new BlockPosition(stored.x, stored.y, stored.z), new OwnerInfo(ownerUuid, playerPearls.playerName), System.currentTimeMillis(), stored.pearlId);
                sendRemovalWarning(trackedPearl);
                sendUnregisterWhisper(trackedPearl.owner(), trackedPearl.pearlId());
                pearlManager.removePearl(ownerUuid, stored.pearlId);
            }
        }
    }

    private boolean withinRemovalRange(BlockPosition position) {
        if (CACHE == null || CACHE.getPlayerCache() == null || CACHE.getPlayerCache().getThePlayer() == null) {
            return false;
        }
        BlockPosition player = blockPositionOf(CACHE.getPlayerCache().getThePlayer());
        double dx = player.x() - position.x();
        double dy = player.y() - position.y();
        double dz = player.z() - position.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distance <= PLUGIN_CONFIG.autoDetect.temporaryRemovalRange;
    }

    /**
     * All registrations living in one chamber. Chambers are shared between players, so a
     * column can hold one entry per player, while chambers stacked in the same column are
     * separated by the y tolerance.
     */
    private List<StoredPearlEntry> findStoredPearlsAtColumn(int blockX, int blockY, int blockZ) {
        List<StoredPearlEntry> found = new ArrayList<>();
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            var playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null) {
                continue;
            }
            for (var pearl : playerPearls.pearls.values()) {
                if (pearl == null || pearl.pearlId == null) {
                    continue;
                }
                if (pearl.x == blockX && pearl.z == blockZ && Math.abs(pearl.y - blockY) <= COLUMN_Y_TOLERANCE) {
                    found.add(new StoredPearlEntry(entry.getKey(), playerPearls.playerName, pearl));
                }
            }
        }
        return found;
    }

    /**
     * The entry of this pearl's own owner in the given chamber. A pearl whose thrower is
     * known only partly is still matched against it, and only a pearl without any owner
     * at all falls back to a chamber holding exactly one entry: a shared one would be
     * ambiguous and stays untouched until the thrower is known.
     */
    private Optional<StoredPearlEntry> resolveStoredEntry(BlockPosition position, OwnerInfo owner) {
        if (position == null) {
            return Optional.empty();
        }
        List<StoredPearlEntry> column = findStoredPearlsAtColumn(position.x(), position.y(), position.z());
        if (column.isEmpty()) {
            return Optional.empty();
        }
        if (owner != null && (owner.uuid() != null || owner.hasName())) {
            return column.stream().filter(entry -> sameOwner(owner, entry.ownerInfo())).findFirst();
        }
        return column.size() == 1 ? Optional.of(column.get(0)) : Optional.empty();
    }

    /**
     * The entry that occupies this chamber. Chambers are exclusive: only one player may
     * hold one, so a pearl thrown by somebody else is checked against whoever is
     * registered here. The owner only breaks the tie when a config still holds more than
     * one entry for the same chamber.
     */
    private Optional<StoredPearlEntry> findStoredPearlInChamber(BlockPosition position, OwnerInfo owner) {
        if (position == null) {
            return Optional.empty();
        }
        List<StoredPearlEntry> chamber = findStoredPearlsAtColumn(position.x(), position.y(), position.z());
        if (chamber.isEmpty()) {
            return Optional.empty();
        }
        if (owner != null) {
            Optional<StoredPearlEntry> mine = chamber.stream()
                    .filter(entry -> sameOwner(owner, entry.ownerInfo()))
                    .findFirst();
            if (mine.isPresent()) {
                return mine;
            }
        }
        return chamber.stream().max(Comparator.comparingInt(entry -> entry.pearl().y));
    }

    /**
     * Pearl ids are only unique per owner, two players in the same config may both hold
     * a "Base", so every id that is announced once is remembered together with its owner.
     */
    private static String ownerScopedId(UUID ownerUuid, String ownerName, String pearlId) {
        String ownerKey = ownerUuid != null ? ownerUuid.toString() : (ownerName == null ? "" : ownerName);
        return ownerKey + "|" + pearlId;
    }

    /**
     * Every pearl id currently stored in the config, scoped by owner. Anything not in
     * here was removed (a pp clear or a del), so its id may be announced again when it
     * comes back.
     */
    private Set<String> configuredPearlIds() {
        Set<String> ids = new HashSet<>();
        for (var entry : PLUGIN_CONFIG.players.entrySet()) {
            var playerPearls = entry.getValue();
            if (playerPearls == null || playerPearls.pearls == null) {
                continue;
            }
            for (var pearl : playerPearls.pearls.values()) {
                if (pearl.pearlId != null && !pearl.pearlId.isBlank()) {
                    ids.add(ownerScopedId(entry.getKey(), playerPearls.playerName, pearl.pearlId));
                }
            }
        }
        return ids;
    }

    private void sendRemovalWarning(TrackedPearl trackedPearl) {
        var builder = Embed.builder()
                .title("Pearl Removal Warning")
                .addField("Owner", trackedPearl.ownerSummary())
                .addField("Pearl", trackedPearl.pearlId() == null ? "unknown" : trackedPearl.pearlId())
                .addField("Position", String.format("||%d %d %d||", trackedPearl.blockX(), trackedPearl.blockY(), trackedPearl.blockZ()));
        discordAndIngameNotification(builder);
    }

    private void sendUnregisterWhisper(OwnerInfo owner, String pearlId) {
        if (owner == null || !owner.hasName()) {
            return;
        }
        String target = owner.name();
        if (target == null || target.isBlank()) {
            return;
        }

        String message = (pearlId == null || pearlId.isBlank())
                ? "A pearl was unregistered."
                : "Pearl " + pearlId + " was unregistered.";

        queueWhisper(target, message);
        info(String.format("Queued pearl removal notice to %s for %s", owner.describe(), pearlId == null ? "unknown pearl" : pearlId));
    }

    private record QueuedWhisper(String target, String message) { }

    private record BlockPosition(int x, int y, int z) {
        boolean sameColumn(BlockPosition other) {
            return other != null && this.x == other.x && this.z == other.z;
        }
    }

    private record OwnerInfo(UUID uuid, String name) {
        boolean hasName() {
            return name != null && !name.isBlank();
        }

        String describe() {
            if (hasName()) {
                return uuid != null ? name + " (" + uuid + ")" : name;
            }
            return uuid != null ? uuid.toString() : "unknown";
        }
    }

    private record StoredPearlEntry(UUID ownerUuid, String ownerName, PearlPlusConfig.StoredPearl pearl) {
        OwnerInfo ownerInfo() {
            return new OwnerInfo(ownerUuid, ownerName);
        }
    }

    private static final class TrackedPearl {
        private BlockPosition position;
        private final Deque<BlockPosition> history = new ArrayDeque<>();
        private OwnerInfo owner;
        private String pearlId;
        private boolean waitingForNameLogged;
        private long lastMovedAt;
        private boolean conflictNotified;
        private boolean registrationNotified;
        private boolean moved;

        TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp) {
            this(position, owner, timestamp, null);
        }

        TrackedPearl(BlockPosition position, OwnerInfo owner, long timestamp, String pearlId) {
            this.position = position;
            this.owner = owner;
            this.lastMovedAt = timestamp;
            this.pearlId = pearlId;
            history.addLast(position);
        }

        void updatePosition(BlockPosition newPosition, long timestamp) {
            if (!newPosition.equals(this.position)) {
                this.position = newPosition;
                this.lastMovedAt = timestamp;
                this.moved = true;
                history.addLast(newPosition);
                while (history.size() > POSITION_HISTORY_LIMIT) {
                    history.removeFirst();
                }
            }
        }

        void setOwner(OwnerInfo owner) {
            this.owner = owner;
        }

        OwnerInfo owner() {
            return owner;
        }

        boolean ownerHasName() {
            return owner != null && owner.hasName();
        }

        String ownerSummary() {
            return owner != null ? owner.describe() : "unknown";
        }

        boolean waitingForNameLogged() {
            return waitingForNameLogged;
        }

        void markWaitingForNameLogged() {
            this.waitingForNameLogged = true;
        }

        void clearWaitingForNameLog() {
            this.waitingForNameLogged = false;
        }

        boolean conflictNotified() {
            return conflictNotified;
        }

        void markConflictNotified() {
            this.conflictNotified = true;
        }

        boolean registrationNotified() {
            return registrationNotified;
        }

        void markRegistrationNotified() {
            this.registrationNotified = true;
        }

        boolean hasMoved() {
            return moved;
        }

        void setPearlId(String pearlId) {
            this.pearlId = pearlId;
        }

        String pearlId() {
            return pearlId;
        }

        boolean hasPearlId() {
            return pearlId != null && !pearlId.isBlank();
        }

        boolean isStable(long now) {
            return now - lastMovedAt >= STABLE_LOCATION_DURATION_MS || hasBouncePattern();
        }

        BlockPosition registrationPosition() {
            if (hasBouncePattern()) {
                return highestRecentBouncePosition();
            }
            return position;
        }

        int blockX() {
            return position.x();
        }

        int blockY() {
            return position.y();
        }

        int blockZ() {
            return position.z();
        }

        private boolean hasBouncePattern() {
            if (history.size() < 5) {
                return false;
            }
            BlockPosition[] points = history.toArray(BlockPosition[]::new);
            BlockPosition latest = points[points.length - 1];
            BlockPosition previous = points[points.length - 2];
            BlockPosition third = points[points.length - 3];
            BlockPosition fourth = points[points.length - 4];
            BlockPosition fifth = points[points.length - 5];

            if (!latest.sameColumn(previous)) {
                return false;
            }
            if (latest.equals(previous)) {
                return false;
            }
            return latest.equals(third) && latest.equals(fifth) && previous.equals(fourth);
        }

        private BlockPosition highestRecentBouncePosition() {
            BlockPosition[] points = history.toArray(BlockPosition[]::new);
            BlockPosition latest = points[points.length - 1];
            BlockPosition previous = points[points.length - 2];
            return latest.y() >= previous.y() ? latest : previous;
        }
    }

}
