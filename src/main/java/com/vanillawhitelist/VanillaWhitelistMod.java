package com.vanillawhitelist;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@Mod(VanillaWhitelistMod.MODID)
public class VanillaWhitelistMod {
    public static final String MODID = "vanillawhitelist";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 通信协议版本。改动协议且不兼容时递增，详见仓库根目录 PROTOCOL.md */
    public static final int PROTOCOL_VERSION = 1;
    /** 实现标识，用于 auth_result */
    public static final String IMPL = "neoforge";

    /**
     * 给任意出站消息盖上 protocol_version。
     * 所有出站消息（推送 + 回复）都经由此处，保证每条消息自描述。
     */
    public static String stampProtocol(String json) {
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            o.addProperty("protocol_version", PROTOCOL_VERSION);
            return new com.google.gson.Gson().toJson(o);
        } catch (Exception e) {
            return json;
        }
    }

    /** 从模组元数据读取自身版本，避免与 gradle.properties 重复维护 */
    public static String implVersion() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(MODID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static VwlConfig config;
    private static Transport transport;
    private static MessageHandler handler;
    private static Database dbInstance;
    private static long tick = 0L;

    public VanillaWhitelistMod() {
        NeoForge.EVENT_BUS.register(this);
        LOGGER.info("[VWL] VanillaWhitelist 模组已加载");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        MinecraftServer server = event.getServer();
        // 仅专用服务端运行：单人世界的集成服务端不应在玩家机器上开监听端口
        if (!server.isDedicatedServer()) {
            LOGGER.info("[VWL] 非专用服务端，跳过 WebSocket 启动");
            return;
        }
        config = VwlConfig.load(FMLPaths.CONFIGDIR.get());
        dbInstance = new Database(FMLPaths.CONFIGDIR.get());
        dbInstance.open();
        Tracker.init(dbInstance);
        StatsCollector.markServerStart();
        handler = new MessageHandler(config);
        handler.setServer(server);
        tick = 0L;
        if (!config.enabled) {
            LOGGER.info("[VWL] 配置中已禁用，不启动 WebSocket");
            return;
        }
        checkSecret();
    }

    // WebSocket 放在 ServerStartedEvent（而非 ServerStartingEvent）启动：
    // 此时 MC 已绑好游戏端口。若入站端口与之冲突，WS 会绑定失败并优雅报错，
    // 而不会抢占端口导致游戏服务端自身启动失败。
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (config == null || !config.enabled || !config.isSecretStrong()) return;
        if (checkPortConflict(server)) return;
        startTransport();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        Tracker.flush();
        if (dbInstance != null) {
            dbInstance.close();
            dbInstance = null;
        }
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        handler = null;
        LOGGER.info("[VWL] 已停止");
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Tracker.onJoin(p);
            push(playerEvent("join", p).toString());
        }
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            long playtime = Tracker.onLeave(p);
            JsonObject o = playerEvent("leave", p);
            o.addProperty("playtime_seconds", playtime);
            push(o.toString());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            JsonObject o = playerEvent("death", p);
            o.addProperty("cause", event.getSource().getMsgId());
            push(o.toString());
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer sp) {
            blockCounter(sp, false);
        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            blockCounter(sp, true);
        }
    }

    private static void blockCounter(ServerPlayer sp, boolean placed) {
        String dim = StatsCollector.dimensionName(sp.level().dimension().identifier());
        if (placed) Tracker.onBlockPlace(sp, dim);
        else Tracker.onBlockBreak(sp, dim);
    }

    @SubscribeEvent
    public void onAdvancement(AdvancementEvent.AdvancementEarnEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            onAdvancementEarned(sp, event.getAdvancement().id().toString());
        }
    }

    /** 成就达成：计数 + 实时推送明细 */
    public static void onAdvancementEarned(ServerPlayer p, String advancementId) {
        Tracker.onAdvancement(p);
        JsonObject o = playerEvent("advancement", p);
        o.addProperty("advancement", advancementId);
        push(o.toString());
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (config == null) return;
        MinecraftServer server = event.getServer();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String to = Tracker.checkDimensionChange(p);
            if (to != null) {
                JsonObject o = playerEvent("dimension_change", p);
                o.addProperty("from", Tracker.previousDimension(p));
                o.addProperty("to", to);
                push(o.toString());
            }
        }
        if (transport == null) return;
        tick++;
        if (tick % (Math.max(1, config.pushIntervalSeconds) * 20L) == 0L) {
            push(StatsCollector.serverStats(server, config).toString());
            JsonObject alert = StatsCollector.checkAlerts(server, config);
            if (alert != null) push(alert.toString());
        }
        if (tick % (Math.max(1, config.worldStatsIntervalSeconds) * 20L) == 0L) {
            push(StatsCollector.worldStats(server, config).toString());
        }
        if (tick % (Math.max(1, config.playerStatsIntervalSeconds) * 20L) == 0L) {
            push(StatsCollector.playerStatsBatch(server, config).toString());
            JsonObject adv = StatsCollector.playerAdvancements(server, config, true);
            if (adv.getAsJsonArray("players").size() > 0) push(adv.toString());
        }
        if (tick % 600L == 0L) Tracker.flush();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    /** 是否入站模式（mode=server） */
    private static boolean isServerMode() {
        return config != null && !"client".equalsIgnoreCase(config.mode);
    }

    private static void startTransport() {
        transport = isServerMode()
                ? new WsServer(config, handler)
                : new WsClient(config, handler);
        handler.setTransport(transport);
        try {
            if (transport instanceof WsServer s) {
                s.setDatabase(dbInstance);
                s.start();
                LOGGER.info("[VWL] 入站模式：WebSocket 服务已启动 {}:{}", config.host, config.port);
            } else if (transport instanceof WsClient c) {
                c.setDatabase(dbInstance);
                c.start();
                LOGGER.info("[VWL] 出站模式：正在连接网站 {}", config.url);
            }
        } catch (Exception e) {
            LOGGER.error("[VWL] WebSocket 启动失败", e);
            transport = null;
        }
    }

    /** 密钥强度校验，与 Paper 行为一致；不合格则拒绝启动 WebSocket */
    private static boolean checkSecret() {
        if (config.isSecretStrong()) return true;
        LOGGER.error("============================================================");
        LOGGER.error(" WebSocket 密钥为空、仍是默认值、或短于 16 字符！");
        LOGGER.error(" 请在 config/vanillawhitelist.json 里设置一个强随机 secret。");
        LOGGER.error(" WebSocket 服务将不会启动。");
        LOGGER.error("============================================================");
        return false;
    }

    /** 端口冲突守卫（仅入站模式） */
    private static boolean checkPortConflict(MinecraftServer server) {
        if (!isServerMode()) return false;
        int gamePort = server.getPort();
        if (config.port != gamePort) return false;
        LOGGER.error("============================================================");
        LOGGER.error(" WebSocket 端口与游戏端口相同：{}", gamePort);
        LOGGER.error(" 已拒绝启动 WebSocket，请把配置里的 port 改成其他值（例如 25585）。");
        LOGGER.error(" 或者改用出站模式：mode = client —— 不需要开放任何端口。");
        LOGGER.error("============================================================");
        return true;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vwl")
                .requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                .then(Commands.literal("status").executes(ctx -> {
                    String mode = config == null ? "?" : (isServerMode() ? "入站(server)" : "出站(client)");
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[VWL] 模式: " + mode
                                    + " | 网站已连接: " + isClientConnected()
                                    + " | 待发队列: " + (dbInstance != null ? dbInstance.queueSize() : 0)), false);
                    return 1;
                }))
                .then(Commands.literal("stats").executes(ctx -> {
                    if (config == null || transport == null) {
                        ctx.getSource().sendFailure(Component.literal("[VWL] WebSocket 未启动"));
                        return 0;
                    }
                    MinecraftServer srv = ctx.getSource().getServer();
                    transport.push(StatsCollector.serverStats(srv, config).toString());
                    ctx.getSource().sendSuccess(() -> Component.literal("[VWL] 已推送一次 server_stats"), false);
                    return 1;
                }))
                .then(Commands.literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(Commands.literal("whitelist")
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> whitelistCommand(ctx, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> whitelistCommand(ctx, false))))));
    }

    private static int whitelistCommand(CommandContext<CommandSourceStack> ctx, boolean add) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "player");
        String err = MessageHandler.applyWhitelist(src.getServer(), name, add);
        if (err == null) {
            src.sendSuccess(() -> Component.literal("[VWL] " + (add ? "已添加白名单: " : "已移除白名单: ") + name), true);
            return 1;
        }
        src.sendFailure(Component.literal("[VWL] 操作失败: " + err));
        return 0;
    }

    private static int reload(CommandSourceStack src) {
        VwlConfig fresh = VwlConfig.load(FMLPaths.CONFIGDIR.get());
        if (transport != null) {
            transport.stop();
            transport = null;
        }
        config = fresh;
        if (handler == null) handler = new MessageHandler(config);
        handler.setServer(src.getServer());
        if (!config.enabled) {
            src.sendSuccess(() -> Component.literal("[VWL] 配置已重载（WebSocket 已禁用）"), false);
            return 1;
        }
        if (!checkSecret()) {
            src.sendFailure(Component.literal("[VWL] 配置已重载，但密钥不合格，WebSocket 未启动"));
            return 0;
        }
        if (checkPortConflict(src.getServer())) {
            src.sendFailure(Component.literal("[VWL] 配置已重载，但端口与游戏端口冲突"));
            return 0;
        }
        startTransport();
        src.sendSuccess(() -> Component.literal("[VWL] 配置已重载，WebSocket 已重启"), false);
        return 1;
    }

    private static JsonObject playerEvent(String event, ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "player_event");
        o.addProperty("event", event);
        o.addProperty("player_name", p.getName().getString());
        o.addProperty("player_uuid", p.getUUID().toString());
        return o;
    }

    private static void push(String json) {
        if (transport != null) transport.push(json);
    }

    public static boolean isClientConnected() {
        return transport != null && transport.isConnected();
    }

    public static VwlConfig config() { return config; }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
