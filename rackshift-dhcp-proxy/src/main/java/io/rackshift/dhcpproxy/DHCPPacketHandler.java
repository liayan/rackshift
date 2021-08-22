package io.rackshift.dhcpproxy;

import com.alibaba.fastjson.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.rackshift.dhcpproxy.constants.ConfigConstants;
import io.rackshift.dhcpproxy.model.Nodes;
import io.rackshift.dhcpproxy.util.ConfigurationUtil;
import io.rackshift.dhcpproxy.util.ConsoleUtil;
import io.rackshift.dhcpproxy.util.DHCPPacketParser;
import org.apache.commons.lang.StringUtils;


public class DHCPPacketHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void messageReceived(ChannelHandlerContext channelHandlerContext, DatagramPacket datagramPacket) {
        JSONObject dhcpPackets = DHCPPacketParser.parse(datagramPacket.content());
        ConsoleUtil.log("received: " + dhcpPackets.toJSONString());
        boolean sendBootfile = isSendBootfile(dhcpPackets);
        ConsoleUtil.log("sendBootfile: " + sendBootfile);
        if (sendBootfile) {
            createDHCPACK(dhcpPackets, getDefaultBootfile(dhcpPackets), channelHandlerContext, datagramPacket);
        }
    }

    private String getDefaultBootfile(JSONObject dhcpPackets) {
        JSONObject options = dhcpPackets.getJSONObject("options");
        if (options == null) {
            throw new RuntimeException("no dhcpPackets options exists!");
        }
        String macs = dhcpPackets.getString("chaddr");
        String userClass = options.getString("userClass");
        String vendorClassIdentifier = options.getString("vendorClassIdentifier");
        int archType = options.getInteger("archType");

        ConsoleUtil.log("macs :" + macs);
        ConsoleUtil.log("userClass :" + userClass);
        ConsoleUtil.log("vendorClassIdentifier :" + vendorClassIdentifier);
        ConsoleUtil.log("archType :" + archType);

        if ("Monorail".equalsIgnoreCase(userClass)) {
            return "http://" + ConfigurationUtil.getConfig(ConfigConstants.APISERVER_URL, "172.31.128.1") + ":"
                    + ConfigurationUtil.getConfig(ConfigConstants.APISERVER_PORT, "9030") + "/api/current/profiles";
        }

        if (vendorClassIdentifier.indexOf("Arista") == 0) {
            // Arista skips the TFTP download step, so just hit the
            // profiles API directly to get a profile from an active task
            // if there is one.
            return "http://" + ConfigurationUtil.getConfig(ConfigConstants.APISERVER_URL, "172.31.128.1") + ":"
                    + ConfigurationUtil.getConfig(ConfigConstants.APISERVER_PORT, "9030") + "/api/current/profiles" +
                    "?macs=" + macs;
        }
        // Same bug as above but for the NICs
        if (macs.toLowerCase().indexOf("ec:a8:6b") == 0) {
            ConsoleUtil.log("Sending down monorail.intel.ipxe for mac address associated with NUCs.");
            return "monorail.intel.ipxe";
        }

        if (StringUtils.isNotBlank(vendorClassIdentifier)) {
            if (archType == 9) {
                return "monorail-efi64-snponly.efi";
            }
            if (archType == 6) {
                return "monorail-efi32-snponly.efi";
            }

            /* Notes for UNDI
             * 1) Some NICs support UNDI driver, it needs chainload ipxe"s undionly.kpxe file to
             * bootup otherwise it will hang using monorail.ipxe. NOTE: if "UNDI" is in class
             * identifier but cannot boot for some NICs, please use MAC address or other
             * condition to judge if use monorail-undionly.kpxe or not, or report it as a bug for
             * this NIC.
             *
             * 2) Some Notes from PXE spec about DHCP options:
             * PXEClient:Arch:xxxxx:UNDI:yyyzzz used for  transactions between client and server.
             * (These strings are case sensitive. This field must not be null terminated.)
             * The information from tags 93 and 94 is embedded in the Class Identifier string
             * xxxxx = Client Sys Architecture 0 . 65535
             * yyy = UNDI Major version 0 . 255
             * zzz = UNDI Minor version 0 . 255
             *
             * The Client Network Interface Identifier specifies the version of the UNDI API
             * (described below) that will support a universal network driver. The UNDI interface
             * must be supported and its version reported in tag #94.
             *
             */
            if (archType == 0 &&
                    vendorClassIdentifier.indexOf("PXEClient:Arch:00000:UNDI") == 0) { // jshint ignore:line
                return "monorail-undionly.kpxe";
            }

            if (archType == 7 &&
                    vendorClassIdentifier.indexOf("PXEClient:Arch:00007:UNDI") == 0) { // jshint ignore:line
                return "monorail-undionly.kpxe";
            }
        }

        return "monorail.ipxe";
    }

    /**
     * @param dhcpPackets
     * @return 真值表
     * 是否存在node 是否有 catalogs 是否有任务运行   是否 sendbootfile
     * 0            0                0                1
     * 1            0                0                1
     * 1            0                1                1
     * 1            1                1                1
     * 1            1                0                0
     * 0            1                0                不存在
     * 0            0                1                不存在
     * 0            1                1  			  不存在
     */
    private boolean isSendBootfile(JSONObject dhcpPackets) {
        if (dhcpPackets.containsKey("chaddr")) {
            String macAddress = dhcpPackets.getString("chaddr");
            Nodes node = Nodes.findByMac(macAddress);
            if (node != null) {
                ConsoleUtil.log("node is exists: " + JSONObject.toJSONString(node));
                if (node.discovered()) {
                    ConsoleUtil.log("node is discovered: " + JSONObject.toJSONString(node));
                } else {
                    ConsoleUtil.log("node is not discovered: " + macAddress);
                }
                if (node.isRunningTask()) {
                    ConsoleUtil.log("node is isRunningTask: " + JSONObject.toJSONString(node));
                } else {
                    ConsoleUtil.log("node is not isRunningTask: " + macAddress);
                }
            } else {
                ConsoleUtil.log("node is not exists: " + macAddress);
            }

            if (node != null && node.discovered()) {
                if (!node.isRunningTask()) {
                    return false;
                }else{
                    return node.isRequestProfile();
                }
            }
            return true;
        }
        return false;
    }

    private void createDHCPACK(JSONObject dhcpPackets, String bootfileName, ChannelHandlerContext channelHandlerContext, DatagramPacket datagramPacket) {
        JSONObject dhcpAckPacket = DHCPPacketParser.createDHCPPROXYAck(dhcpPackets, bootfileName);
        ByteBuf byteBuf = DHCPPacketParser.createDHCPPROXYAckBuffer(dhcpAckPacket);
        channelHandlerContext.writeAndFlush(new DatagramPacket(byteBuf, datagramPacket.sender())).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture f) {
                if (!f.isSuccess()) {
                    System.out.print("DHCP Proxy send datagramPacket error:");
                    f.cause().printStackTrace();
                }
            }
        });
        ConsoleUtil.log("send: " + "http://" + ConfigurationUtil.getConfig(ConfigConstants.TFTP_URL, "172.31.128.1") + "/" + bootfileName + " ! to" + datagramPacket.sender() + " macaddress:" + dhcpAckPacket.getString("chaddr"));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.print("DHCP Proxy receving datagramPacket error:");
        cause.printStackTrace();
    }
}
