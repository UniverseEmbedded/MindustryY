package mindustry.campaign.shared.net;

import arc.struct.*;
import arc.util.*;
import mindustry.campaign.shared.*;
import mindustry.campaign.shared.io.*;
import mindustry.campaign.shared.runtime.*;
import mindustry.runtime.*;
import mindustry.net.*;
import mindustry.net.Packets.*;

import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Admission support for isolated shared-campaign action servers.
 *
 * Credentials are carried in the initial {@link ConnectPacket} as a reserved pseudo-mod entry. This deliberately
 * avoids changing the base packet wire layout, so ordinary Mindustry connections remain protocol-compatible with
 * unmodified servers. The action server removes the reserved entry before normal Mod compatibility checks, validates
 * it before a {@code Player} is created or world data is sent, and consumes its nonce exactly once.
 */
public class SharedCampaignNet implements AutoCloseable{
    private static final String admissionPrefix = "mindustry-shared-action-admission:";
    private static final String legacyAdmissionPrefix = "mindustry-y-action-admission:";

    /** Client join/rebind state is isolated from server admission state so a stuck admission callback cannot deadlock hot-switch UI. */
    private final ReentrantLock clientLock = new ReentrantLock(true);

    private final ObjectMap<NetConnection, PendingAdmission> pendingAdmissions = new ObjectMap<>();
    private final ObjectSet<NetConnection> approvedSpectators = new ObjectSet<>();
    /** Authenticated Shared Campaign member identity bound to each admitted action connection. */
    private final ObjectMap<NetConnection, String> approvedMemberIds = new ObjectMap<>();
    private final ObjectMap<String, Long> consumedNonces = new ObjectMap<>();
    /** Short-lived one-shot direct grants used only by the pure-vanilla reconnect compatibility lane. */
    private final ObjectMap<String, VanillaAdmission> vanillaAdmissions = new ObjectMap<>();
    private volatile String pendingActionId = "", pendingJoinToken = "", pendingMemberId = "";
    /** Client-generated opaque broker connection id; it identifies the exact game TCP during same-member hot-switches. */
    private volatile String pendingBrokerSessionId = "", connectingBrokerSessionId = "", activeBrokerSessionId = "";
    /** True only when the next game TCP targets the coordinator's shared entry and therefore needs an MYCS preface. */
    private volatile boolean pendingSharedEntry;
    private volatile String connectingClientActionId = "", activeClientActionId = "";
    private volatile boolean pendingSpectator, connectingSpectator;
    /** Client-only presentation intent: a newly launched sector should replay the vanilla core landing after streaming. */
    private volatile boolean pendingLandingPresentation, connectingLandingPresentation;
    // UI-test-only admission fault controls. These never activate in production and exist so product Journeys can
    // exercise the real ConnectPacket/server-admission path instead of replacing it with protocol mocks.
    private volatile String uiTestNextAdmissionFault = "", uiTestPreviousPreparedToken = "";
    private volatile long uiTestNextAdmissionDelayMillis;

    public void install(){
        //Admission is integrated directly into NetClient/NetServer's initial ConnectPacket path.
    }

    public void updateRegistration(){
        long now = Time.millis();
        pruneConsumedNonces(now);
        pruneVanillaAdmissions(now);
    }

    /**
     * Installs one direct vanilla admission on this Action. The coordinator has already authenticated the member and
     * source Action over MYCT; the vanilla client itself cannot carry an HMAC token, so the grant is deliberately
     * short-lived, one-shot and bound to both the persistent Mindustry UUID seed and observed source address.
     */
    public synchronized void prepareVanillaAdmission(String grantId, String networkUuid, String memberId, String remoteAddress, boolean spectator, long expiresAt){
        String id = clean(grantId), uuid = clean(networkUuid), member = clean(memberId), address = normalizeRemoteAddress(remoteAddress);
        long now = Time.millis();
        if(id.isBlank()) throw new IllegalArgumentException("Vanilla admission grant ID is required");
        if(uuid.isBlank()) throw new IllegalArgumentException("Mindustry network UUID is required");
        if(member.isBlank()) throw new IllegalArgumentException("Shared Campaign member ID is required");
        if(address.isBlank()) throw new IllegalArgumentException("Source client address is required");
        if(expiresAt <= now || expiresAt - now > 60_000L) throw new IllegalArgumentException("Vanilla admission lifetime must be positive and no longer than 60 seconds");
        pruneVanillaAdmissions(now);
        vanillaAdmissions.put(uuid, new VanillaAdmission(id, uuid, member, address, spectator, expiresAt));
    }

    /** Idempotently revokes exactly the prepared grant, without deleting a newer grant for the same client UUID. */
    public synchronized boolean revokeVanillaAdmission(String grantId, String networkUuid){
        String id = clean(grantId), uuid = clean(networkUuid);
        VanillaAdmission current = vanillaAdmissions.get(uuid);
        if(current == null || !current.grantId.equals(id)) return false;
        vanillaAdmissions.remove(uuid);
        return true;
    }

    /** Supplies a one-use credential for the next server connection attempt. */
    public void prepareJoin(String actionId, String token, boolean spectator){
        prepareJoin(actionId, token, spectator, false, "", false);
    }

    public void prepareJoin(String actionId, String token, boolean spectator, boolean landingPresentation){
        prepareJoin(actionId, token, spectator, landingPresentation, "", false);
    }

    /**
     * Prepares the next Action join. {@code sharedEntry} means the TCP endpoint is the coordinator broker rather than
     * a direct ArcNet server; ArcNetProvider will then send an inline MYCS routing preface before waiting for
     * RegisterTCP and use TCP-only transport for that connection.
     */
    public void prepareJoin(String actionId, String token, boolean spectator, boolean landingPresentation, String memberId, boolean sharedEntry){
        clientLock.lock();
        try{
            pendingActionId = actionId == null ? "" : actionId;
            pendingMemberId = memberId == null ? "" : memberId;
            pendingSharedEntry = sharedEntry;
            pendingBrokerSessionId = sharedEntry ? java.util.UUID.randomUUID().toString() : "";
            String prepared = token == null ? "" : token;
            if(Boolean.getBoolean("mindustry.uiTest")){
                String fault = uiTestNextAdmissionFault;
                uiTestNextAdmissionFault = "";
                if("invalid".equals(fault)){
                    prepared = corruptTokenForTesting(prepared);
                }else if("replay".equals(fault)){
                    if(uiTestPreviousPreparedToken.isBlank()) throw new IllegalStateException("No previous Shared Campaign admission token is available for replay testing");
                    prepared = uiTestPreviousPreparedToken;
                }else if(!fault.isBlank() && !"delay".equals(fault)){
                    throw new IllegalArgumentException("Unknown Shared Campaign admission test fault: " + fault);
                }
                if(!"replay".equals(fault) && !prepared.isBlank() && !"invalid".equals(fault)) uiTestPreviousPreparedToken = prepared;
            }
            pendingJoinToken = prepared;
            pendingSpectator = spectator;
            pendingLandingPresentation = landingPresentation && !spectator;
        }finally{
            clientLock.unlock();
        }
    }

    /** Prepares the ConnectPacket after a same-TCP broker rebind; no new routing preface is sent on this path. */
    public void prepareRebindJoin(String actionId, String token, boolean spectator, boolean landingPresentation, String memberId, String brokerSessionId){
        clientLock.lock();
        try{
            applyRebindJoin(actionId, token, spectator, landingPresentation, memberId, brokerSessionId);
        }finally{
            clientLock.unlock();
        }
    }

    /**
     * Same as {@link #prepareRebindJoin} but fails closed after {@code timeoutMillis} instead of blocking forever.
     * This is the hot-switch path used by the strategic planet UI: if another callback wedges join preparation,
     * the caller can fall back to an ordinary reconnect and always dismiss {@code ui.loadfrag}.
     */
    public boolean tryPrepareRebindJoin(String actionId, String token, boolean spectator, boolean landingPresentation,
                                        String memberId, String brokerSessionId, long timeoutMillis) throws InterruptedException{
        if(!clientLock.tryLock(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS)) return false;
        try{
            applyRebindJoin(actionId, token, spectator, landingPresentation, memberId, brokerSessionId);
            return true;
        }finally{
            clientLock.unlock();
        }
    }

    private void applyRebindJoin(String actionId, String token, boolean spectator, boolean landingPresentation, String memberId, String brokerSessionId){
        pendingActionId = actionId == null ? "" : actionId;
        pendingJoinToken = token == null ? "" : token;
        pendingMemberId = memberId == null ? "" : memberId;
        pendingSharedEntry = false;
        pendingBrokerSessionId = brokerSessionId == null ? "" : brokerSessionId;
        pendingSpectator = spectator;
        pendingLandingPresentation = landingPresentation && !spectator;
    }

    /** Clears only the not-yet-connected join attempt, preserving the currently displayed Action identity. */
    public void clearPreparedJoin(){
        clientLock.lock();
        try{
            clearPreparedJoinLocked();
        }finally{
            clientLock.unlock();
        }
    }

    private void clearPreparedJoinLocked(){
        pendingActionId = "";
        pendingJoinToken = "";
        pendingMemberId = "";
        pendingSharedEntry = false;
        pendingBrokerSessionId = "";
        pendingSpectator = false;
        pendingLandingPresentation = false;
        connectingSpectator = false;
        connectingLandingPresentation = false;
        connectingClientActionId = "";
        connectingBrokerSessionId = "";
    }

    /** Test/diagnostic visibility for the next prepared Action. */
    public String preparedActionId(){
        clientLock.lock();
        try{ return pendingActionId; }
        finally{ clientLock.unlock(); }
    }

    public void clearPending(){
        clientLock.lock();
        try{
            clearPreparedJoinLocked();
            activeClientActionId = "";
            activeBrokerSessionId = "";
        }finally{
            clientLock.unlock();
        }
    }

    /**
     * Returns the unframed broker routing preface for the pending join, or {@code null} for an ordinary/direct server
     * connection. The signed admission token is intentionally duplicated here: the broker verifies it before routing,
     * while the Action world verifies the token carried later by ConnectPacket plus UUID/nonce constraints.
     */
    public byte[] clientConnectionPreamble(){
        clientLock.lock();
        try{
            if(!pendingSharedEntry || pendingActionId.isBlank() || pendingJoinToken.isBlank()) return null;
            return ActionSessionHandshake.encode(new ActionSessionHandshake.Request(
                ActionSessionHandshake.version, ActionSessionHandshake.Kind.action, ActionSessionHandshake.flagInlineArc,
                pendingBrokerSessionId, pendingActionId, pendingMemberId, pendingJoinToken, 0));
        }finally{
            clientLock.unlock();
        }
    }

    /** Called by NetClient immediately before the initial ConnectPacket is sent. */
    public void decorateConnectPacket(ConnectPacket packet){
        clientLock.lock();
        try{
            String actionId = pendingActionId, token = pendingJoinToken, brokerSessionId = pendingBrokerSessionId;
            boolean spectator = pendingSpectator;
            boolean landingPresentation = pendingLandingPresentation;
            if(Boolean.getBoolean("mindustry.uiTest")){
                long delay = uiTestNextAdmissionDelayMillis;
                uiTestNextAdmissionDelayMillis = 0L;
                if(delay > 0L){
                    try{ Thread.sleep(Math.min(delay, 30_000L)); }
                    catch(InterruptedException interrupted){ Thread.currentThread().interrupt(); }
                }
            }
            pendingActionId = "";
            pendingJoinToken = "";
            pendingMemberId = "";
            pendingSharedEntry = false;
            pendingBrokerSessionId = "";
            pendingSpectator = false;
            pendingLandingPresentation = false;
            connectingSpectator = false;
            connectingLandingPresentation = false;
            if(actionId.isBlank() || token.isBlank()) return;
            connectingClientActionId = actionId;
            connectingBrokerSessionId = brokerSessionId;
            connectingSpectator = spectator;
            connectingLandingPresentation = landingPresentation;
            if(packet.mods == null) packet.mods = new Seq<>();
            packet.mods.add(admissionPrefix + actionId + ":" + token);
        }finally{
            clientLock.unlock();
        }
    }

    /**
     * Removes and captures the reserved credential before normal Mod compatibility logic. No cryptographic decision is
     * made here, so an otherwise rejected connection does not consume a one-use grant.
     */
    public synchronized void captureConnectAdmission(NetConnection connection, ConnectPacket packet){
        pendingAdmissions.remove(connection);
        if(packet.mods == null) return;

        String actionId = null, token = null;
        for(int i = packet.mods.size - 1; i >= 0; i--){
            String entry = packet.mods.get(i);
            if(entry == null) continue;
            String prefix = entry.startsWith(admissionPrefix) ? admissionPrefix : entry.startsWith(legacyAdmissionPrefix) ? legacyAdmissionPrefix : null;
            if(prefix == null) continue;
            packet.mods.remove(i);
            if(actionId != null){
                //Multiple credentials are never valid; preserve a sentinel that will fail closed later.
                actionId = "";
                token = "";
                continue;
            }
            String value = entry.substring(prefix.length());
            int separator = value.indexOf(':');
            if(separator <= 0 || separator == value.length() - 1){
                actionId = "";
                token = "";
            }else{
                actionId = value.substring(0, separator);
                token = value.substring(separator + 1);
            }
        }
        if(actionId != null){
            String platformUuid = ConnectPacket.platformUuid(packet.uuid);
            pendingAdmissions.put(connection, new PendingAdmission(actionId, token, platformUuid == null ? "" : platformUuid));
        }
    }

    /**
     * Performs the final admission check after ordinary identity/version/Mod validation but before Player creation and
     * world transmission. Returns false after kicking the connection on any failure.
     */
    public synchronized boolean validateActionAdmission(NetConnection connection, ConnectPacket packet){
        PendingAdmission pending = pendingAdmissions.remove(connection);
        boolean actionServer = isActionServer();

        if(!actionServer){
            if(pending != null){
                connection.kick("This server is not the requested Shared Campaign action.", 0);
                return false;
            }
            return true;
        }

        if(pending == null || pending.actionId.isBlank() || pending.token.isBlank()){
            if(validateVanillaAdmission(connection, packet)) return true;
            connection.kick("A valid Shared Campaign action invitation is required.", 0);
            return false;
        }

        ActionRuntimeConfig runtime = currentActionRuntime();
        String expectedAction = runtime.actionId();
        String actionSecret = runtime.joinSecret();
        long now = Time.millis();
        CoordinatorCredentials.JoinGrant grant = CoordinatorCredentials.verifyActionJoinToken(actionSecret, pending.token, expectedAction, pending.networkUuid, now);
        if(grant == null || !expectedAction.equals(pending.actionId)){
            CoordinatorCredentials.JoinTokenDiagnostics diagnostic = CoordinatorCredentials.diagnoseActionJoinToken(actionSecret, pending.token, expectedAction, pending.networkUuid, now);
            SharedCampaignState snapshot = authoritativeState();
            SharedCampaignState.ActionState campaignAction = snapshot == null ? null : snapshot.actions.get(expectedAction);
            String advertisedHash = campaignAction == null ? "" : campaignAction.joinSecretHash;
            String runtimeHash = actionSecret == null || actionSecret.isBlank() ? "" : SharedCampaignCodec.sha256(ControlProtocol.deriveKey(actionSecret));
            Log.warn("[SharedCampaign] Action admission rejected: context=@ explicitContext=@ pendingActionMatch=@ tokenWellFormed=@ signatureValid=@ actionMatch=@ networkUuidMatch=@ timeValid=@ tokenVersion=@ issuedDeltaMs=@ expiresDeltaMs=@ runtimeJoinKeyHash=@ campaignJoinKeyHash=@ joinKeyHashMatch=@",
                RuntimeContexts.requireCurrent().id, RuntimeContexts.hasExplicitCurrent(), expectedAction.equals(pending.actionId), diagnostic.wellFormed(), diagnostic.signatureValid(), diagnostic.actionMatches(), diagnostic.networkUuidMatches(), diagnostic.timeValid(), diagnostic.version(),
                diagnostic.issuedAt() == 0L ? Long.MIN_VALUE : diagnostic.issuedAt() - diagnostic.now(), diagnostic.expiresAt() == 0L ? Long.MIN_VALUE : diagnostic.expiresAt() - diagnostic.now(),
                hashPrefix(runtimeHash), hashPrefix(advertisedHash), !runtimeHash.isBlank() && runtimeHash.equals(advertisedHash));
            connection.kick("The Shared Campaign action invitation is invalid or expired.", 0);
            return false;
        }

        SharedCampaignState authoritative = authoritativeState();
        if(!grant.memberId().isBlank() && authoritative != null && !memberAuthorized(authoritative, grant.memberId())){
            connection.kick("This Shared Campaign membership has been revoked.", 0);
            return false;
        }

        pruneConsumedNonces(now);
        Long previousExpiry = consumedNonces.get(grant.nonce());
        if(previousExpiry != null && previousExpiry >= now){
            connection.kick("This Shared Campaign action invitation has already been used.", 0);
            return false;
        }
        consumedNonces.put(grant.nonce(), grant.expiresAt());
        if(!grant.memberId().isBlank()) approvedMemberIds.put(connection, grant.memberId());
        if(grant.spectator()) approvedSpectators.add(connection);
        return true;
    }


    private synchronized boolean validateVanillaAdmission(NetConnection connection, ConnectPacket packet){
        long now = Time.millis();
        pruneVanillaAdmissions(now);
        String networkUuid = ConnectPacket.platformUuid(packet.uuid);
        if(networkUuid == null || networkUuid.isBlank()) return false;
        VanillaAdmission grant = vanillaAdmissions.get(networkUuid);
        if(grant == null || grant.expiresAt < now) return false;
        // Do not consume a legitimate grant when somebody from another address guesses/copies the UUID.
        if(connection == null || !grant.remoteAddress.equals(normalizeRemoteAddress(connection.address))) return false;
        SharedCampaignState authoritative = authoritativeState();
        if(authoritative == null || !memberAuthorized(authoritative, grant.memberId)){
            vanillaAdmissions.remove(networkUuid);
            return false;
        }
        vanillaAdmissions.remove(networkUuid);
        approvedMemberIds.put(connection, grant.memberId);
        if(grant.spectator) approvedSpectators.add(connection);
        return true;
    }

    /**
     * Applies the role requested by this client after the ordinary world stream has created its local Player. The
     * server independently validates and enforces the signed grant, so this value only controls local input/UI.
     */
    public boolean consumeClientSpectator(){
        boolean value = connectingSpectator;
        connectingSpectator = false;
        return value;
    }

    /** Consumes the one-shot late-join presentation requested by a fresh Shared sector launch. */
    public boolean consumeClientLandingPresentation(){
        boolean value = connectingLandingPresentation;
        connectingLandingPresentation = false;
        return value;
    }

    /** Commits the Shared Campaign action identity only after the world stream has completed. */
    public void finishClientActionConnection(){
        clientLock.lock();
        try{
            activeClientActionId = connectingClientActionId;
            activeBrokerSessionId = connectingBrokerSessionId;
            connectingClientActionId = "";
            connectingBrokerSessionId = "";
        }finally{
            clientLock.unlock();
        }
    }

    /** Returns the action currently displayed by this client, or an empty string for an ordinary server. */
    public String activeClientActionId(){ return activeClientActionId; }

    /** Active or still-connecting Action identity for disconnect diagnostics; empty outside a Shared Action connection. */
    public String clientActionIdSnapshot(){
        clientLock.lock();
        try{
            return activeClientActionId.isBlank() ? connectingClientActionId : activeClientActionId;
        }finally{
            clientLock.unlock();
        }
    }
    /** Exact broker session for the currently displayed Action; empty for direct/ordinary servers. */
    public String activeBrokerSessionId(){ return activeBrokerSessionId; }

    public boolean activeClientAction(){ return !activeClientActionId.isBlank(); }

    /** True while a Shared Action game connection is being admitted or is already displaying a world. */
    public boolean clientActionConnection(){ return !connectingClientActionId.isBlank() || !activeClientActionId.isBlank(); }

    /** Restricted product-test hook: corrupt/replay/delay the next real action admission without bypassing networking. */
    public void configureAdmissionFaultForTesting(String mode, long delayMillis){
        if(!Boolean.getBoolean("mindustry.uiTest")) throw new SecurityException("Shared Campaign admission fault injection requires mindustry.uiTest=true");
        String clean = mode == null ? "" : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if(!clean.isBlank() && !clean.equals("invalid") && !clean.equals("replay") && !clean.equals("delay")) throw new IllegalArgumentException("Unsupported admission fault: " + mode);
        uiTestNextAdmissionFault = clean;
        uiTestNextAdmissionDelayMillis = Math.max(0L, Math.min(delayMillis, 30_000L));
    }

    private static String corruptTokenForTesting(String token){
        if(token == null || token.isBlank()) return "ui-test-invalid-admission";
        int last = token.length() - 1;
        char current = token.charAt(last);
        char replacement = current == 'A' ? 'B' : 'A';
        return token.substring(0, last) + replacement;
    }

    public void clearClientActionConnection(){
        clientLock.lock();
        try{
            connectingClientActionId = "";
            activeClientActionId = "";
            connectingBrokerSessionId = "";
            activeBrokerSessionId = "";
            connectingSpectator = false;
        }finally{
            clientLock.unlock();
        }
    }

    /** Consumes the authenticated spectator role exactly once after Player creation. */
    public synchronized boolean consumeSpectator(NetConnection connection){
        return approvedSpectators.remove(connection);
    }

    /**
     * Returns the Shared Campaign identity authenticated by this connection's signed action admission grant. An empty
     * value means the connection predates member-bound admission and must not be promoted to a campaign member merely
     * from its unrelated Mindustry network UUID.
     */
    public synchronized String authenticatedMemberId(NetConnection connection){
        if(connection == null) return "";
        String memberId = approvedMemberIds.get(connection);
        return memberId == null ? "" : memberId;
    }

    public synchronized void discardConnectAdmission(NetConnection connection){
        pendingAdmissions.remove(connection);
        approvedSpectators.remove(connection);
        approvedMemberIds.remove(connection);
    }

    /**
     * Reconciles already-admitted gameplay connections against the authoritative campaign membership snapshot.
     * Membership removal is a live authority boundary: a player may not remain in an Action merely because their
     * short-lived admission token was valid before the durable removal committed.
     */
    public synchronized int reconcileMembership(SharedCampaignState snapshot){
        if(snapshot == null) return 0;
        Seq<NetConnection> revoked = new Seq<>();
        approvedMemberIds.each((connection, memberId) -> {
            if(!memberAuthorized(snapshot, memberId)) revoked.add(connection);
        });
        for(NetConnection connection : revoked){
            approvedSpectators.remove(connection);
            approvedMemberIds.remove(connection);
            try{ connection.kick("Your Shared Campaign membership has been revoked.", 0); }catch(Throwable ignored){}
        }
        return revoked.size;
    }

    private static boolean memberAuthorized(SharedCampaignState snapshot, String memberId){
        if(snapshot == null || memberId == null || memberId.isBlank()) return false;
        return java.util.Objects.equals(memberId, snapshot.ownerId) || snapshot.members.containsKey(memberId);
    }

    private void pruneConsumedNonces(long now){
        synchronized(this){
            Seq<String> expired = new Seq<>();
            consumedNonces.each((nonce, expiry) -> { if(expiry < now) expired.add(nonce); });
            for(String nonce : expired) consumedNonces.remove(nonce);
        }
    }

    private void pruneVanillaAdmissions(long now){
        synchronized(this){
            Seq<String> expired = new Seq<>();
            vanillaAdmissions.each((uuid, grant) -> { if(grant.expiresAt < now) expired.add(uuid); });
            for(String uuid : expired) vanillaAdmissions.remove(uuid);
        }
    }

    private static String clean(String value){ return value == null ? "" : value.trim(); }

    private static String normalizeRemoteAddress(String value){
        String address = clean(value);
        if(address.startsWith("/")) address = address.substring(1);
        int slash = address.lastIndexOf('/');
        if(slash >= 0) address = address.substring(slash + 1);
        if(address.startsWith("[")){
            int close = address.indexOf(']');
            if(close > 1) return address.substring(1, close);
        }
        int colon = address.lastIndexOf(':');
        if(colon > 0 && address.indexOf(':') == colon){
            String port = address.substring(colon + 1);
            if(!port.isBlank() && port.chars().allMatch(Character::isDigit)) address = address.substring(0, colon);
        }
        return address;
    }

    private static boolean isActionServer(){
        return currentActionRuntime().enabled();
    }

    /** Authority-state provider attached by the Shared Campaign service without coupling networking to the service type. */
    public interface CampaignStateSource{
        SharedCampaignState state();
    }

    public static SharedCampaignNet install(GameContext context){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.install(context);
        SharedCampaignNet current = shared.component(SharedCampaignNet.class);
        if(current != null) return current;
        return shared.attach(SharedCampaignNet.class, new SharedCampaignNet());
    }

    public static SharedCampaignNet find(GameContext context){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(context);
        return shared == null ? null : shared.component(SharedCampaignNet.class);
    }

    private static ActionRuntimeConfig currentActionRuntime(){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(RuntimeContexts.requireCurrent());
        return shared == null ? ActionRuntimeConfig.disabled() : shared.actionRuntime();
    }

    private static SharedCampaignState authoritativeState(){
        SharedCampaignRuntimeState shared = SharedCampaignRuntimeState.find(RuntimeContexts.requireCurrent());
        CampaignStateSource source = shared == null ? null : shared.component(CampaignStateSource.class);
        return source == null ? null : source.state();
    }

    @Override
    public synchronized void close(){
        pendingAdmissions.clear();
        approvedSpectators.clear();
        approvedMemberIds.clear();
        consumedNonces.clear();
        vanillaAdmissions.clear();
        clearPending();
    }

    private static String hashPrefix(String value){
        return value == null || value.isBlank() ? "missing" : value.substring(0, Math.min(12, value.length()));
    }

    private record PendingAdmission(String actionId, String token, String networkUuid){}
    private record VanillaAdmission(String grantId, String networkUuid, String memberId, String remoteAddress, boolean spectator, long expiresAt){}
}
