package main.com.pyratron.pugmatt.bedrockconnect.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import main.com.pyratron.pugmatt.bedrockconnect.*;
import main.com.pyratron.pugmatt.bedrockconnect.config.Whitelist;
import main.com.pyratron.pugmatt.bedrockconnect.config.Custom.CustomEntry;
import main.com.pyratron.pugmatt.bedrockconnect.config.Custom.CustomServer;
import main.com.pyratron.pugmatt.bedrockconnect.config.Custom.CustomServerGroup;
import main.com.pyratron.pugmatt.bedrockconnect.logging.LogColors;
import main.com.pyratron.pugmatt.bedrockconnect.server.gui.MainFormButton;
import main.com.pyratron.pugmatt.bedrockconnect.server.gui.ManageFormButton;
import main.com.pyratron.pugmatt.bedrockconnect.server.gui.UIComponents;
import main.com.pyratron.pugmatt.bedrockconnect.server.gui.UIForms;

import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.protocol.bedrock.BedrockServerSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.AttributeData;
import org.cloudburstmc.protocol.bedrock.data.PacketCompressionAlgorithm;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult.IdentityData;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.PacketSignal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.PublicKey;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

public class PacketHandler implements BedrockPacketHandler {
    private BedrockServerSession session;

    private String name;
    private String uuid;
    private IdentityData extraData;

    private BCPlayer player;

    // Used for server icon fix
    private ScheduledThreadPoolExecutor executor = null;

     public PacketHandler(BedrockServerSession session, boolean packetListening) {
        this.session = session;
    }

    public void setPlayer(BCPlayer player) {
        this.player = player;
    }

    public String getIP(String hostname) {
        try {
            if(BedrockConnect.getConfig().canFetchFeaturedIps() || BedrockConnect.getConfig().canFetchIps()) {
                InetAddress host = InetAddress.getByName(hostname);
                String address = host.getHostAddress();
                BedrockConnect.logger.debug("Retrieved " + address + " host address from hostname " + hostname);
                return address;
            } else {
                return BedrockConnect.getConfig().getFeaturedServerIps().get(hostname);
            }
        } catch (UnknownHostException ex) {
            BedrockConnect.logger.error("Error retrieving IP from hostname", ex);
        }
        return hostname;
    }

    @Override
    public PacketSignal handlePacket(BedrockPacket packet) {
        if (BedrockConnect.getConfig().isDebugEnabled() && !(packet instanceof PlayerAuthInputPacket)) {
            String id = session.getSocketAddress().toString();
            if (name != null) {
                id = name;
            }
            if (packet instanceof LoginPacket) {
                BedrockConnect.logger.debug(LogColors.gray("[ " + id + " ] " + "LoginPacket"));
            } else {
                BedrockConnect.logger.debug(LogColors.gray("[ " + id + " ] " + packet));
            }
        }
        BedrockPacketHandler.super.handlePacket(packet);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(RequestChunkRadiusPacket packet) {
        ChunkRadiusUpdatedPacket chunkRadiusUpdatePacket = new ChunkRadiusUpdatedPacket();
        chunkRadiusUpdatePacket.setRadius(packet.getRadius());
        session.sendPacketImmediately(chunkRadiusUpdatePacket);

        PlayStatusPacket playStatus = new PlayStatusPacket();
        playStatus.setStatus(PlayStatusPacket.Status.PLAYER_SPAWN);
        session.sendPacket(playStatus);
        return PacketSignal.HANDLED;
    }

    // Occasionally, a sent form will not correctly send to a player for whatever reason, and they float in space. This works as a way to open the form back up.

    @Override
    public PacketSignal handle(PlayerActionPacket packet) {
        player.movementOpen();
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(AnimatePacket packet) {
        if(packet.getAction() == AnimatePacket.Action.SWING_ARM)
            player.movementOpen();
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ModalFormResponsePacket packet) {
        player.setActive();
        player.resetMovementOpen();
        
        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Packet Response: " + packet.getFormId() );
        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Packet Data: " + packet.getFormData() );
        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Current Form Data: " + player.getCurrentFormData() );
        
        switch (packet.getFormId()) {
            case UIForms.CREATE_WORLD:
                try {
                    if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if(player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MANAGE_SERVER);
                    } else {
                        JsonObject currentFormDataJson = JsonParser.parseString(player.getCurrentFormData()).getAsJsonObject();
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());

                        if(data.size() == 3) {
                            ProcessBuilder pb;
                            Process process;

                            boolean managerFinished;
                            String managerResult;
                            String[] worldPorts;
                            String type = currentFormDataJson.getAsJsonArray("content").get(1).getAsJsonObject().getAsJsonArray("options").get(Integer.parseInt(data.get(1))).getAsString();

                            // Call minecraft-server-manager to create world
                            
                            pb = new ProcessBuilder(
                                "sudo",
                                BedrockConnect.getConfig().getManagerPath(),
                                "create",
                                "--player_xuid", player.getUuid(),
                                "--world_name", data.get(0),
                                "--type", type,
                                "--server_version", "latest",
                                "--seed", String.format("\"%s\"", data.get(2))
                            );
                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Starting manager");
                            pb.redirectErrorStream(true);
                            process = pb.start();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                            managerFinished = process.waitFor(5, TimeUnit.SECONDS);
                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] After manager");
                            if(managerFinished) {
                                //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Manager finished");
                                switch (process.exitValue()) {
                                    case 0:    // OK
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Manager Exit:0");
                                        managerResult = reader.readLine();
                                        
                                        String address = BedrockConnect.getConfig().getHostName();
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Host:" + address);
                                        String port = managerResult.split("\\|")[0];
                                        String name = data.get(0);
                                        
                                        if(UIComponents.validateServerInfo(address, port, name, player)) {
                                            BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Server info validated");
                                            player.addServer(address, port, name);
                                        }
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Sending form");
                                        player.openForm(UIForms.MANAGE_SERVER);
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Finished case:0");
                                        break;
                                    // fall-through known error returns
                                    case 10:   // Missing player_xuid
                                    case 11:   // Invalid characters in xuid ([a-f0-9]-)
                                    case 12:   // player_xuid is the wrong length (36 characters)
                                    case 13:   // Invalid player_xuid format (^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$)
                                    case 20:   // Missing world_name
                                    case 21:   // Invalid characters in world_name ([a-zA-Z0-9]- )
                                    case 22:   // world_name too long (Max 30 characters)
                                    case 23:   // World with world_name already exists
                                    case 30:   // Missing type
                                    case 31:   // Invalid type
                                    case 42:   // Invalid version (default: latest)
                                    case 52:   // Seed too long (Max 32 characters)
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] "+ BedrockConnect.getConfig().getLanguage().getWording("createWorld", "error"+process.exitValue()));
                                        player.createError(BedrockConnect.getConfig().getLanguage().getWording("createWorld", "error"+process.exitValue()));
                                        break;
                                    default:
                                        BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Unknown exit code:" + process.exitValue());
                                        player.createError(BedrockConnect.getConfig().getLanguage().getWording("createWorld", "UnknownExitCode"));
                                        break;
                                }

                            } else {
                                BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("createWorld", "WorldCreationTimeout"));
                                player.createError(BedrockConnect.getConfig().getLanguage().getWording("createWorld", "WorldCreationTimeout"));
                            }
                        } else {
                            BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("createWorld", "InvalidWorldInformation"));
                            player.createError(BedrockConnect.getConfig().getLanguage().getWording("createWorld", "InvalidWorldInformation"));
                        }
                    }
                } catch(Exception e) {
                    BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("creatWorld", "UnkonwnServerCreateError"));
                    player.createError(BedrockConnect.getConfig().getLanguage().getWording("creatWorld", "UnkownServerCreateError"));
                }
                break;
            case UIForms.DELETE_WORLD:
                try {
                    if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if(player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MANAGE_SERVER);
                    } else {
                        JsonObject currentFormDataJson = JsonParser.parseString(player.getCurrentFormData()).getAsJsonObject();
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());

                        if(data.size() == 1) {
                            ProcessBuilder pb;
                            Process process;

                            boolean managerFinished;
                            String managerResult;
                            String worldName = currentFormDataJson.getAsJsonArray("content").get(0).getAsJsonObject().getAsJsonArray("options").get(Integer.parseInt(data.get(0))).getAsString();

                            // Call minecraft-server-manager to create world
                            
                            pb = new ProcessBuilder(
                                "sudo",
                                BedrockConnect.getConfig().getManagerPath(),
                                "delete",
                                "--player_xuid", player.getUuid(),
                                "--world_name", worldName
                            );
                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Starting manager");
                            pb.redirectErrorStream(true);
                            process = pb.start();
                            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                            managerFinished = process.waitFor(5, TimeUnit.SECONDS);
                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] After manager");
                            if(managerFinished) {
                                //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Manager finished");
                                switch (process.exitValue()) {
                                    case 0:    // OK
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Manager Exit:0");
                                        managerResult = reader.readLine();
                                        
                                        String address = BedrockConnect.getConfig().getHostName();
                                        BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Hostname: " + address);
                                        String port = managerResult.split("\\|")[0];
                                        
                                        if(UIComponents.validateServerInfo(address, port, worldName, player)) {
                                            BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Server info validated");
                                            player.removeServer(address, port, worldName);
                                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Sending form");
                                            player.openForm(UIForms.MANAGE_SERVER);
                                            //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Finished case:0");
                                        }
                                        break;
                                    // fall-through known error returns
                                    case 10:   // Missing player_xuid
                                    case 11:   // Invalid characters in xuid ([a-f0-9]-)
                                    case 12:   // player_xuid is the wrong length (36 characters)
                                    case 13:   // Invalid player_xuid format (^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$)
                                    case 20:   // Missing world_name
                                    case 21:   // Invalid characters in world_name ([a-zA-Z0-9]- )
                                    case 22:   // world_name too long (Max 30 characters)
                                    case 24:   // World with world_name doesn't exists
                                        //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] "+ BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "error"+process.exitValue()));
                                        player.createError(BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "error"+process.exitValue()));
                                        break;
                                    default:
                                        BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Unknown exit code:" + process.exitValue());
                                        player.createError(BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "UnknownExitCode"));
                                        break;
                                }

                            } else {
                                BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "WorldCreationTimeout"));
                                player.createError(BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "WorldDeletionTimeout"));
                            }
                        } else {
                            BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "InvalidWorldInformation"));
                            player.createError(BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "InvalidWorldInformation"));
                        }
                    }
                } catch(Exception e) {
                    BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] " + BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "UnkonwnServerCreateError"));
                    player.createError(BedrockConnect.getConfig().getLanguage().getWording("deleteWorld", "UnkownServerDeleteError"));
                }
                break;
            case UIForms.MOTD:
                // Store datetime of when motd was viewed, if we need it for tracking when to show it again
                if (BedrockConnect.getConfig().isMotdCooldownEnabled()) {
                    BedrockConnect.getDataUtil().setViewedMotd(player.getUuid());
                }
                player.openForm(UIForms.MAIN);
                break;
            case UIForms.MAIN:
                // Re-open window if closed
                if (packet.getFormData() == null || packet.getFormData().contains("null")) {
                    if(player.getCurrentForm() != packet.getFormId())
                        return PacketSignal.HANDLED;
                    player.openForm(UIForms.MAIN);
                } else { // If selecting button
                    int chosen = Integer.parseInt(packet.getFormData().replaceAll("\\s+",""));

                    CustomEntry[] customServers = BedrockConnect.getConfig().getCustomServers();
                    List<String> playerServers = player.getServerList();

                    MainFormButton button = UIForms.getMainFormButton(chosen, customServers, playerServers);

                    int serverIndex = UIForms.getServerIndex(chosen, customServers, playerServers);

                    switch(button) {
                        case CONNECT:
                            player.openForm(UIForms.DIRECT_CONNECT);
                            break;
                        case MANAGE:
                            player.openForm(UIForms.MANAGE_SERVER);
                            break;
                        case EXIT:
                            player.disconnect(BedrockConnect.getConfig().getLanguage().getWording("disconnect", "exit"));
                            break;
                        case USER_SERVER:
                            String address = player.getServerList().get(serverIndex);

                            if (address.split(":").length > 1) {
                                String ip = address.split(":")[0];
                                String port = address.split(":")[1];

                                transfer(ip, Integer.parseInt(port));
                            } else {
                                player.createError((BedrockConnect.getConfig().getLanguage().getWording("error", "invalidUserServer")));
                            }
                            break;
                        case CUSTOM_SERVER:
                            CustomEntry server = customServers[serverIndex - playerServers.size()];

                            if(server instanceof CustomServer) {
                                transfer(((CustomServer)server).getAddress(), ((CustomServer)server).getPort());
                            } else if(server instanceof CustomServerGroup) {
                                player.setSelectedGroup(serverIndex - playerServers.size());
                                player.openForm(UIForms.SERVER_GROUP);
                            }
                            break;
                        case FEATURED_SERVER:
                            int featuredServer = serverIndex - playerServers.size() - customServers.length;

                            switch (featuredServer) {
                                case 0: // Hive
                                    transfer(getIP("geo.hivebedrock.network"), 19132);
                                    break;
                                case 1: // Cubecraft
                                    transfer(!BedrockConnect.getConfig().canFetchFeaturedIps() ? getIP("mco.cubecraft.net") : "mco.cubecraft.net", 19132);
                                    break;
                                case 2: // Lifeboat
                                    transfer(getIP("mco.lbsg.net"), 19132);
                                    break;
                                case 3: // Mineville
                                    transfer(getIP("play.inpvp.net"), 19132);
                                    break;
                                case 4: // Galaxite
                                    transfer(getIP("play.galaxite.net"), 19132);
                                    break;
                                case 5: // Enchanted Dragons
                                    transfer(getIP("play.enchanted.gg"), 19132);
                                    break;
                            }
                            break;
                    }
                }
                break;
            case UIForms.SERVER_GROUP:
                if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                    if(player.getCurrentForm() != packet.getFormId())
                        return PacketSignal.HANDLED;
                    player.openForm(UIForms.MAIN);
                }
                else {
                    int chosen = Integer.parseInt(packet.getFormData().replaceAll("\\s+",""));

                    CustomEntry[] customServers = BedrockConnect.getConfig().getCustomServers();
                    CustomServerGroup group = (CustomServerGroup) customServers[player.getSelectedGroup()];

                    if(chosen == 0) {
                        player.openForm(UIForms.MAIN);
                    } else {
                        CustomServer server = group.getServers().get(chosen - 1);
                        transfer(server.getAddress(), server.getPort());
                    }
                }
                break;
            case UIForms.MANAGE_SERVER:
                //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Player Current Form: " + player.getCurrentForm() );
                if(packet.getFormData() == null) {
                    if(player.getCurrentForm() != packet.getFormId())
                        return PacketSignal.HANDLED;
                    player.openForm(UIForms.MAIN);
                }
                else {
                    int chosen = Integer.parseInt(packet.getFormData().replaceAll("\\s+",""));
                    //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Chosen: " + chosen);

                    ManageFormButton button = UIForms.getManageFormButton(chosen);
                    //BedrockConnect.logger.info("[ " + LogColors.purple("Tracing") + " ] Button: " + button);

                    switch(button) {
                        case ADD:
                            player.openForm(UIForms.ADD_SERVER);
                            break;
                        case EDIT:
                            player.openForm(UIForms.EDIT_CHOOSE_SERVER);
                            break;
                        case REMOVE:
                            player.openForm(UIForms.REMOVE_SERVER);
                            break;
                        case CREATE:
                            player.openForm(UIForms.CREATE_WORLD);
                            break;
                        case DELETE:
                            player.openForm(UIForms.DELETE_WORLD);
                            break;
                    }
                }
                break;
            case UIForms.ADD_SERVER:
                try {
                    if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if(player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MANAGE_SERVER);
                    }
                    else {
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());
                        if(data.size() > 1) {
                            // Remove any whitespace
                            data = UIComponents.cleanAddress(data);

                            String address = data.get(0);
                            String port = data.get(1);
                            String name = data.get(2);

                            if(UIComponents.validateServerInfo(address, port, name, player)) {
                                player.addServer(address, port, name);

                                player.openForm(UIForms.MANAGE_SERVER);
                            }
                        }
                    }
                } catch(Exception e) {
                    player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "invalidServerConnect"));
                }
                break;
            case UIForms.DIRECT_CONNECT:
                try {
                    if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if(player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MAIN);
                    }
                    else {
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());
                        if(data.size() > 1) {
                            // Remove any whitespace
                            data = UIComponents.cleanAddress(data);

                            String address = data.get(0);
                            String port = data.get(1);
                            String name = data.get(2);

                            if(UIComponents.validateServerInfo(address, port, name, player)) {
                                boolean addServer = Boolean.parseBoolean(data.get(3));
                                if (addServer) {
                                    if(player.addServer(address, port, name)) {
                                        transfer(address.replace(" ", ""), Integer.parseInt(port));
                                    }
                                } else {
                                    transfer(address.replace(" ", ""), Integer.parseInt(port));
                                }
                            }
                        }
                    }
                } catch(Exception e) {
                    player.createError((BedrockConnect.getConfig().getLanguage().getWording("error", "invalidServerConnect")));
                }
                break;
            case UIForms.EDIT_CHOOSE_SERVER:
                try {
                    if (packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if (player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MANAGE_SERVER);
                    } else {
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());

                        int chosen = Integer.parseInt(data.get(0));

                        String server = player.getServerList().get(chosen);

                        String[] serverInfo = UIComponents.validateAddress(server, player);

                        if (serverInfo != null) {
                            String ip = serverInfo[0];
                            String port = serverInfo[1];
                            String name = serverInfo.length > 2 ? serverInfo[2] : "";

                            player.setEditingServer(chosen);

                            session.sendPacketImmediately(UIForms.createEditServer(ip, port, name));
                            player.setCurrentForm(UIForms.EDIT_SERVER);
                        }
                    }
                } catch(Exception e) {
                    player.createError((BedrockConnect.getConfig().getLanguage().getWording("error", "invalidServerEdit")));
                }
                break;
            case UIForms.EDIT_SERVER:
                if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                    if(player.getCurrentForm() != packet.getFormId())
                        return PacketSignal.HANDLED;
                    player.openForm(UIForms.EDIT_CHOOSE_SERVER);
                }
                else {
                    ArrayList<String> data = UIComponents.getFormData(packet.getFormData());
                    if(data.size() > 1) {
                        // Remove any whitespace
                        data.set(0, data.get(0).replaceAll("\\s",""));
                        data.set(1, data.get(1).replaceAll("\\s",""));

                        String address = data.get(0);
                        String port = data.get(1);
                        String name = data.get(2);

                        if(UIComponents.validateServerInfo(address, port, name, player)) {
                            String value = address + ":" + port;
                            if(!name.isEmpty()) value += ":" + name;

                            List<String> servers = player.getServerList();
                            servers.set(player.getEditingServer(), value);

                            player.setServerList(servers);

                            player.openForm(UIForms.EDIT_CHOOSE_SERVER);
                        }
                    }
                }
                break;
            case UIForms.REMOVE_SERVER:
                try {
                    if(packet.getFormData() == null || packet.getFormData().contains("null")) {
                        if(player.getCurrentForm() != packet.getFormId())
                            return PacketSignal.HANDLED;
                        player.openForm(UIForms.MANAGE_SERVER);
                    }
                    else {
                        ArrayList<String> data = UIComponents.getFormData(packet.getFormData());

                        int chosen = Integer.parseInt(data.get(0));

                        List<String> serverList = player.getServerList();
                        serverList.remove(chosen);

                        player.setServerList(serverList);

                        player.openForm(UIForms.MANAGE_SERVER);
                    }
                } catch(Exception e) {
                    player.createError((BedrockConnect.getConfig().getLanguage().getWording("error", "invalidServerRemove")));
                }
                break;
            case UIForms.ERROR:
                // Reopen previous form before error
                player.openForm(player.getCurrentForm());
                break;
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(NetworkStackLatencyPacket packet) {
        // Fix bug where server icons don't load
        UpdateAttributesPacket updateAttributesPacket = new UpdateAttributesPacket();
        updateAttributesPacket.setRuntimeEntityId(1);
        List<AttributeData> attributes = Collections.singletonList(new AttributeData("minecraft:player.level", 0f, 24791.00f, 0, 0f));
        updateAttributesPacket.setAttributes(attributes);

        if (executor == null)
            executor = new ScheduledThreadPoolExecutor(1);

        executor.schedule(() -> {
            session.sendPacket(updateAttributesPacket);
        }, 500, TimeUnit.MILLISECONDS);

        return PacketSignal.HANDLED;
    }

    public void transfer(String ip, int port) {
        try {
            TransferPacket tp = new TransferPacket();
            if(BedrockConnect.getConfig().canFetchIps() && UIComponents.isDomain(ip)) {
                tp.setAddress(getIP(ip));
            } else {
                tp.setAddress(ip);
            }
            tp.setPort(port);
            session.sendPacketImmediately(tp);
            BedrockConnect.logger.debug("Transferred player " + name + " to " + tp.getAddress() + ":" + tp.getPort());
        } catch (Exception e) {
            player.createError(BedrockConnect.getConfig().getLanguage().getWording("error", "transferError"));
        }
    }

    @Override
    public PacketSignal handle(SetLocalPlayerAsInitializedPacket packet) {
        if (BedrockConnect.getConfig().getMotdMessage() != null && player.canShowMotd()) {
            player.openForm(UIForms.MOTD);
        } else {
            player.openForm(UIForms.MAIN);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(RequestNetworkSettingsPacket packet) {
        int protocolVersion = packet.getProtocolVersion();

        BedrockCodec packetCodec = BedrockProtocol.getBedrockCodec(packet.getProtocolVersion());

        if (packetCodec == null) {
            PlayStatusPacket status = new PlayStatusPacket();
            if (protocolVersion > BedrockProtocol.DEFAULT_BEDROCK_CODEC.getProtocolVersion()) {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD);
            } else {
                status.setStatus(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD);
            }

            session.sendPacketImmediately(status);
            return PacketSignal.HANDLED;
        }
        session.setCodec(packetCodec);

        PacketCompressionAlgorithm algorithm = PacketCompressionAlgorithm.ZLIB;

        NetworkSettingsPacket responsePacket = new NetworkSettingsPacket();
        responsePacket.setCompressionAlgorithm(algorithm);
        responsePacket.setCompressionThreshold(0);
        session.sendPacketImmediately(responsePacket);

        session.setCompression(algorithm);
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        if(executor != null)
            executor.shutdown();
        if(player != null)
            BedrockConnect.getServer().removePlayer(player);
         BedrockConnect.logger.info("[ " + LogColors.cyan(BedrockConnect.getServer().getPlayers().size() + " online") + " ] Player disconnected: " + name + " (uuid: " + uuid + ")");
    }
    
    @Override
    public PacketSignal handle(ResourcePackClientResponsePacket packet) {
        switch (packet.getStatus()) {
            case COMPLETED:
                BedrockConnect.getDataUtil().initializePlayerData(uuid, name, session, this);
                break;
            case HAVE_ALL_PACKS:
                ResourcePackStackPacket rs = new ResourcePackStackPacket();
                //rs.setExperimental(false);
                rs.setForcedToAccept(false);
                rs.setGameVersion("*");
                rs.setForcedToAccept(false);
                session.sendPacket(rs);
                break;
            default:
                session.disconnect("disconnectionScreen.resourcePack");
                break;
        }

        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(LoginPacket packet) {
        try {
            ChainValidationResult result = EncryptionUtils.validatePayload(packet.getAuthPayload());
            if (BedrockConnect.getConfig().isOnlineModeEnabled() && !result.signed()) {
                throw new RuntimeException("Chain not signed");
            }
            PublicKey identityPublicKey = result.identityClaims().parsedIdentityPublicKey();

            byte[] clientDataPayload = EncryptionUtils.verifyClientData(packet.getClientJwt(), identityPublicKey);
            if (clientDataPayload == null) {
                throw new IllegalStateException("Client data isn't signed by the given chain data");
            }

            if (result.identityClaims().extraData == null) {
                throw new RuntimeException("AuthData was not found!");
            }

            extraData = result.identityClaims().extraData;

            BedrockConnect.logger.debug("Player made it through login: " + extraData.displayName + " (uuid: " + extraData.identity + ")");

            if (!result.signed()) {
               BedrockConnect.logger.debug("Chain not signed: " + extraData.displayName + " (uuid: " + extraData.identity + ")");
            }

            name = extraData.displayName;
            uuid = extraData.identity.toString();
            xuid = extraData.xuid;
            
            // Whitelist check
            Whitelist whitelist = BedrockConnect.getConfig().getWhitelist();
            if (whitelist.hasWhitelist() && !whitelist.isPlayerWhitelisted(name)) {
            	session.disconnect(whitelist.getWhitelistMessage());
            	BedrockConnect.logger.info("Kicked " + name + " (uuid: " + uuid + "): \"" + whitelist.getWhitelistMessage() + "\"");
            }

            PlayStatusPacket status = new PlayStatusPacket();
            status.setStatus(PlayStatusPacket.Status.LOGIN_SUCCESS);
            session.sendPacket(status);

            SetEntityMotionPacket motion = new SetEntityMotionPacket();
            motion.setRuntimeEntityId(1);
            motion.setMotion(Vector3f.ZERO);
            session.sendPacket(motion);

            ResourcePacksInfoPacket resourcePacksInfo = new ResourcePacksInfoPacket();
            resourcePacksInfo.setForcedToAccept(false);
            resourcePacksInfo.setScriptingEnabled(false);
            resourcePacksInfo.setWorldTemplateId(UUID.randomUUID());
            resourcePacksInfo.setWorldTemplateVersion("*");
            session.sendPacket(resourcePacksInfo);
        } catch (Exception e) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete login", e);
        }
        return PacketSignal.HANDLED;
    }
}
