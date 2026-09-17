package com.acceptRulesBeforeJoinPlugin;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.eclipse.sisu.Priority;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class AcceptRulesBeforeJoinPlugin extends JavaPlugin {

    //这是一个极小的插件，所以直接在主类里面写所有代码，包括配置读取

    //配置相关
    private static String titleText;
    private static List<String> bodyTexts;
    private static int waitingSeconds;
    private static String dialog_priority;
    private static final int INVALID_INT = Integer.MIN_VALUE;

    private static ServerJoinListener serverJoinListener;

    @Override
    public void onEnable() {
        //处理配置
        saveDefaultConfig();
        FileConfiguration config = getConfig();

        titleText = config.getString("title_text");
        bodyTexts = config.getStringList("body_texts");
        waitingSeconds = config.getInt("waiting_seconds", INVALID_INT);
        dialog_priority = config.getString("dialog_priority");

        //检查各配置字段是否正确
        boolean shouldDisablePlugin = false;

        if(titleText == null || titleText.isEmpty()) {
            getLogger().severe("Field 'title_text' in the config must not be empty!");
            shouldDisablePlugin = true;
        }
        if(bodyTexts == null || bodyTexts.isEmpty()) {
            getLogger().severe("Field 'body_texts' in the config must not be empty!");
            shouldDisablePlugin = true;
        }
        if(waitingSeconds <= 0) {
            getLogger().severe("Field 'waiting_seconds' in the config must not be empty and must greater than 0!");
            shouldDisablePlugin = true;
        }

        EventPriority priority = EventPriority.NORMAL;
        if(dialog_priority == null || dialog_priority.isEmpty()) {
            getLogger().severe("Field 'dialog_priority' in the config must not be empty!");
            shouldDisablePlugin = true;
        }
        switch (dialog_priority) {
            case "HIGHEST" -> priority = EventPriority.HIGHEST;
            case "HIGH" -> priority = EventPriority.HIGH;
            case "NORMAL" -> priority = EventPriority.NORMAL;
            case "LOW" -> priority = EventPriority.LOW;
            case "LOWEST" -> priority = EventPriority.LOWEST;
            default -> {
                getLogger().severe("Field 'dialog_priority' in the config is incorrect!");
                shouldDisablePlugin = true;
            }
        }

        if(shouldDisablePlugin) {
            getLogger().severe("Failed to parse the config. This plugin will be disabled! Please check your config.yml.");
            getServer().getPluginManager().disablePlugin(this);
        }

        //如果配置正确，开始注册事件，先实例化监听器
        serverJoinListener = new ServerJoinListener();

        //逐个注册守护监听器
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvent(
                AsyncPlayerConnectionConfigureEvent.class,
                serverJoinListener,
                priority,
                (l, e) -> serverJoinListener.onPlayerConfigure((AsyncPlayerConnectionConfigureEvent) e),
                this
                );

        pm.registerEvent(
                PlayerCustomClickEvent.class,
                serverJoinListener,
                EventPriority.NORMAL,
                (l, e) -> serverJoinListener.onHandleDialog((PlayerCustomClickEvent) e),
                this
        );

        pm.registerEvent(
                PlayerConnectionCloseEvent.class,
                serverJoinListener,
                EventPriority.NORMAL,
                (l, e) -> serverJoinListener.onConnectionClose((PlayerConnectionCloseEvent) e),
                this

        );
    }

    @Override
    public void onDisable() {
        getLogger().info("The plugin has been disabled!");
        // Plugin shutdown logic
    }


    private static final class ServerJoinListener implements Listener {
        private final Map<UUID, CompletableFuture<Boolean>> awaitingResponse = new ConcurrentHashMap<>();

        @EventHandler()
        void onPlayerConfigure(AsyncPlayerConnectionConfigureEvent event) {
            Dialog dialog = createDialog();
            if (dialog == null) {
                return;
            }

            PlayerConfigurationConnection connection = event.getConnection();
            UUID pid = connection.getProfile().getId();
            if (pid == null) {
                return;
            }

            CompletableFuture<Boolean> response = new CompletableFuture<>();
            response.completeOnTimeout(false, waitingSeconds, TimeUnit.SECONDS);

            awaitingResponse.put(pid, response);

            Audience audience = connection.getAudience();
            audience.showDialog(dialog);

            if(!response.join()){
                audience.closeDialog();
                connection.disconnect(Component.text(":(\n\n你拒绝了服务器准则\nYou rejected the server rules", NamedTextColor.RED, TextDecoration.BOLD));
            }

            awaitingResponse.remove(pid);
        }

        @EventHandler
        void onHandleDialog(PlayerCustomClickEvent event) {
            if(!(event.getCommonConnection() instanceof PlayerConfigurationConnection configurationConnection)){
                return;
            }

            UUID pid = configurationConnection.getProfile().getId();
            if(pid == null){
                return;
            }

            Key key = event.getIdentifier();
            if(key.equals(Key.key("arbjp:rule_dialog/agree"))){
                setConnectionJoinResult(pid, true);
            }
            else if(key.equals(Key.key("arbjp:rule_dialog/reject"))){
                setConnectionJoinResult(pid, false);
            }
        }

        @EventHandler
        void onConnectionClose(PlayerConnectionCloseEvent event) {
            awaitingResponse.remove(event.getPlayerUniqueId());
        }

        private void setConnectionJoinResult(UUID pid, boolean value){
            CompletableFuture<Boolean> future = awaitingResponse.get(pid);
            if(future != null){
                future.complete(value);
            }
        }

        private Dialog createDialog(){
            List<DialogBody> bodies = new ArrayList<>();
            for(String line : bodyTexts){
                bodies.add(DialogBody.plainMessage(Component.text(line)));
            }
            Dialog dialog = Dialog.create(builder -> {
                builder.empty()
                        .base(DialogBase.builder(Component.text(titleText))
                                .canCloseWithEscape(false)
                                .body(bodies)
                                .build()
                        )
                        .type(DialogType.confirmation(
                                ActionButton.builder(Component.text("I AGREE!", NamedTextColor.GREEN, TextDecoration.BOLD))
                                        .tooltip(Component.text("Click to agree the rules and join the server!", NamedTextColor.WHITE))
                                        .action(DialogAction.customClick(Key.key("arbjp:rule_dialog/agree"), null))
                                        .build(),

                                ActionButton.builder(Component.text("REJECT AND LEAVE", NamedTextColor.RED, TextDecoration.BOLD))
                                        .tooltip(Component.text("Click to reject the rules and you will be kicked.", NamedTextColor.WHITE))
                                        .action(DialogAction.customClick(Key.key("arbjp:rule_dialog/reject"), null))
                                        .build()
                        ));
            });

            return dialog;
        }
    }
}
