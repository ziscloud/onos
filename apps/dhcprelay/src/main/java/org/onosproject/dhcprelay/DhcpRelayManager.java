/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.dhcprelay;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Dictionary;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.ARP;
import org.onlab.packet.DHCP;
import org.onlab.packet.DHCP6;
import org.onlab.packet.IPacket;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.VlanId;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.dhcprelay.api.DhcpHandler;
import org.onosproject.dhcprelay.api.DhcpRelayService;
import org.onosproject.dhcprelay.store.DhcpRecord;
import org.onosproject.dhcprelay.store.DhcpRelayStore;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.provider.ProviderId;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;
/**
 * DHCP Relay Agent Application Component.
 */
@Component(immediate = true)
@Service
public class DhcpRelayManager implements DhcpRelayService {
    public static final String DHCP_RELAY_APP = "org.onosproject.dhcp-relay";
    public static final ProviderId PROVIDER_ID = new ProviderId("host", DHCP_RELAY_APP);
    public static final String HOST_LOCATION_PROVIDER =
            "org.onosproject.provider.host.impl.HostLocationProvider";
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final InternalConfigListener cfgListener = new InternalConfigListener();

    private final Set<ConfigFactory> factories = ImmutableSet.of(
            new ConfigFactory<ApplicationId, DhcpRelayConfig>(APP_SUBJECT_FACTORY,
                    DhcpRelayConfig.class,
                    "dhcprelay") {
                @Override
                public DhcpRelayConfig createConfig() {
                    return new DhcpRelayConfig();
                }
            }
    );

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DhcpRelayStore dhcpRelayStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService compCfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY,
               target = "(version=4)")
    protected DhcpHandler v4Handler;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY,
               target = "(version=6)")
    protected DhcpHandler v6Handler;

    @Property(name = "arpEnabled", boolValue = true,
             label = "Enable Address resolution protocol")
    protected boolean arpEnabled = true;

    private DhcpRelayPacketProcessor dhcpRelayPacketProcessor = new DhcpRelayPacketProcessor();
    private InternalHostListener hostListener = new InternalHostListener();
    private ApplicationId appId;

    @Activate
    protected void activate(ComponentContext context) {
        //start the dhcp relay agent
        appId = coreService.registerApplication(DHCP_RELAY_APP);

        cfgService.addListener(cfgListener);
        factories.forEach(cfgService::registerConfigFactory);
        //update the dhcp server configuration.
        updateConfig();

        //add the packet processor
        packetService.addProcessor(dhcpRelayPacketProcessor, PacketProcessor.director(0));

        // listen host event for dhcp server or the gateway
        hostService.addListener(hostListener);
        requestDhcpPackets();
        modified(context);

        // disable dhcp from host location provider
        compCfgService.preSetProperty(HOST_LOCATION_PROVIDER,
                                      "useDhcp", Boolean.FALSE.toString());
        compCfgService.registerProperties(getClass());
        log.info("DHCP-RELAY Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(cfgListener);
        factories.forEach(cfgService::unregisterConfigFactory);
        packetService.removeProcessor(dhcpRelayPacketProcessor);
        hostService.removeListener(hostListener);
        cancelDhcpPackets();
        cancelArpPackets();
        v4Handler.getDhcpGatewayIp().ifPresent(hostService::stopMonitoringIp);
        v4Handler.getDhcpServerIp().ifPresent(hostService::stopMonitoringIp);
        // TODO: DHCPv6 Handler

        compCfgService.unregisterProperties(getClass(), false);
        log.info("DHCP-RELAY Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        Boolean flag;

        flag = Tools.isPropertyEnabled(properties, "arpEnabled");
        if (flag != null) {
            arpEnabled = flag;
            log.info("Address resolution protocol is {}",
                     arpEnabled ? "enabled" : "disabled");
        }

        if (arpEnabled) {
            requestArpPackets();
        } else {
            cancelArpPackets();
        }
    }

    private void updateConfig() {
        DhcpRelayConfig cfg = cfgService.getConfig(appId, DhcpRelayConfig.class);
        if (cfg == null) {
            log.warn("Dhcp Server info not available");
            return;
        }
        Optional<IpAddress> oldDhcpServerIp = v4Handler.getDhcpServerIp();
        Optional<IpAddress> oldDhcpGatewayIp = v4Handler.getDhcpGatewayIp();
        v4Handler.setDhcpServerConnectPoint(cfg.getDhcpServerConnectPoint());
        v4Handler.setDhcpServerIp(cfg.getDhcpServerIp());
        v4Handler.setDhcpGatewayIp(cfg.getDhcpGatewayIp());
        v4Handler.setDhcpConnectMac(null);
        v4Handler.setDhcpConnectVlan(null);

        log.info("DHCP server connect point: " + cfg.getDhcpServerConnectPoint());
        log.info("DHCP server ipaddress " + cfg.getDhcpServerIp());

        IpAddress ipToProbe = v4Handler.getDhcpGatewayIp().isPresent() ? cfg.getDhcpGatewayIp() :
                                                                         cfg.getDhcpServerIp();
        String hostToProbe = v4Handler.getDhcpGatewayIp().isPresent() ? "gateway" : "DHCP server";

        // TODO: DHCPv6 server config
        Set<Host> hosts = hostService.getHostsByIp(ipToProbe);
        if (hosts.isEmpty()) {
            log.info("Probing to resolve {} IP {}", hostToProbe, ipToProbe);
            oldDhcpGatewayIp.ifPresent(hostService::stopMonitoringIp);
            oldDhcpServerIp.ifPresent(hostService::stopMonitoringIp);
            hostService.startMonitoringIp(ipToProbe);
        } else {
            // Probe target is known; There should be only 1 host with this ip
            hostUpdated(hosts.iterator().next());
        }
    }

    private void hostRemoved(Host host) {
        v4Handler.getDhcpServerIp().ifPresent(ip -> {
            if (host.ipAddresses().contains(ip)) {
                log.warn("DHCP server {} removed", ip);
                v4Handler.setDhcpConnectMac(null);
                v4Handler.setDhcpConnectVlan(null);
            }
        });
        v4Handler.getDhcpGatewayIp().ifPresent(ip -> {
            if (host.ipAddresses().contains(ip)) {
                log.warn("DHCP gateway {} removed", ip);
                v4Handler.setDhcpConnectMac(null);
                v4Handler.setDhcpConnectVlan(null);
            }
        });
        // TODO: v6 handler
    }

    private void hostUpdated(Host host) {
        v4Handler.getDhcpGatewayIp().ifPresent(ip -> {
            if (host.ipAddresses().contains(ip)) {
                log.warn("DHCP gateway {} removed", ip);
                v4Handler.setDhcpConnectMac(host.mac());
                v4Handler.setDhcpConnectVlan(host.vlan());
            }
        });
        v4Handler.getDhcpServerIp().ifPresent(ip -> {
            if (host.ipAddresses().contains(ip)) {
                log.warn("DHCP server {} removed", ip);
                v4Handler.setDhcpConnectMac(host.mac());
                v4Handler.setDhcpConnectVlan(host.vlan());
            }
        });
        // TODO: v6 handler
    }

    /**
     * Request DHCP packet in via PacketService.
     */
    private void requestDhcpPackets() {
        TrafficSelector.Builder selectorServer = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(selectorServer.build(), PacketPriority.CONTROL, appId);

        TrafficSelector.Builder selectorClient = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.requestPackets(selectorClient.build(), PacketPriority.CONTROL, appId);
    }

    /**
     * Cancel requested DHCP packets in via packet service.
     */
    private void cancelDhcpPackets() {
        TrafficSelector.Builder selectorServer = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.cancelPackets(selectorServer.build(), PacketPriority.CONTROL, appId);

        TrafficSelector.Builder selectorClient = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
        packetService.cancelPackets(selectorClient.build(), PacketPriority.CONTROL, appId);
    }

    /**
     * Request ARP packet in via PacketService.
     */
    private void requestArpPackets() {
        TrafficSelector.Builder selectorArpServer = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selectorArpServer.build(), PacketPriority.CONTROL, appId);
    }

    /**
     * Cancel requested ARP packets in via packet service.
     */
    private void cancelArpPackets() {
        TrafficSelector.Builder selectorArpServer = DefaultTrafficSelector.builder()
                .matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selectorArpServer.build(), PacketPriority.CONTROL, appId);
    }

    @Override
    public Optional<DhcpRecord> getDhcpRecord(HostId hostId) {
        return dhcpRelayStore.getDhcpRecord(hostId);
    }

    @Override
    public Collection<DhcpRecord> getDhcpRecords() {
        return dhcpRelayStore.getDhcpRecords();
    }

    @Override
    public Optional<MacAddress> getDhcpServerMacAddress() {
        return v4Handler.getDhcpConnectMac();
    }

    /**
     * Gets DHCP data from a packet.
     *
     * @param packet the packet
     * @return the DHCP data; empty if it is not a DHCP packet
     */
    private Optional<DHCP> findDhcp(Ethernet packet) {
        return Stream.of(packet)
                .filter(Objects::nonNull)
                .map(Ethernet::getPayload)
                .filter(p -> p instanceof IPv4)
                .map(IPacket::getPayload)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof UDP)
                .map(IPacket::getPayload)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof DHCP)
                .map(p -> (DHCP) p)
                .findFirst();
    }

    /**
     * Gets DHCPv6 data from a packet.
     *
     * @param packet the packet
     * @return the DHCPv6 data; empty if it is not a DHCPv6 packet
     */
    private Optional<DHCP6> findDhcp6(Ethernet packet) {
        return Stream.of(packet)
                .filter(Objects::nonNull)
                .map(Ethernet::getPayload)
                .filter(p -> p instanceof IPv6)
                .map(IPacket::getPayload)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof UDP)
                .map(IPacket::getPayload)
                .filter(Objects::nonNull)
                .filter(p -> p instanceof DHCP6)
                .map(p -> (DHCP6) p)
                .findFirst();
    }


    private class DhcpRelayPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            // process the packet and get the payload
            Ethernet packet = context.inPacket().parsed();
            if (packet == null) {
                return;
            }

            findDhcp(packet).ifPresent(dhcpPayload -> {
                v4Handler.processDhcpPacket(context, dhcpPayload);
            });

            findDhcp6(packet).ifPresent(dhcp6Payload -> {
                v6Handler.processDhcpPacket(context, dhcp6Payload);
            });

            if (packet.getEtherType() == Ethernet.TYPE_ARP && arpEnabled) {
                ARP arpPacket = (ARP) packet.getPayload();
                VlanId vlanId = VlanId.vlanId(packet.getVlanID());
                Set<Interface> interfaces = interfaceService.
                        getInterfacesByPort(context.inPacket().receivedFrom());
                //ignore the packets if dhcp server interface is not configured on onos.
                if (interfaces.isEmpty()) {
                    log.warn("server virtual interface not configured");
                    return;
                }
                if ((arpPacket.getOpCode() != ARP.OP_REQUEST)) {
                    // handle request only
                    return;
                }
                MacAddress interfaceMac = interfaces.stream()
                        .filter(iface -> iface.vlan().equals(vlanId))
                        .map(Interface::mac)
                        .filter(mac -> !mac.equals(MacAddress.NONE))
                        .findFirst()
                        .orElse(MacAddress.ONOS);
                if (interfaceMac == null) {
                    // can't find interface mac address
                    return;
                }
                processArpPacket(context, packet, interfaceMac);
            }
        }

        /**
         * Processes the ARP Payload and initiates a reply to the client.
         *
         * @param context the packet context
         * @param packet the ethernet payload
         * @param replyMac mac address to be replied
         */
        private void processArpPacket(PacketContext context, Ethernet packet, MacAddress replyMac) {
            ARP arpPacket = (ARP) packet.getPayload();
            ARP arpReply = (ARP) arpPacket.clone();
            arpReply.setOpCode(ARP.OP_REPLY);

            arpReply.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());
            arpReply.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
            arpReply.setSenderProtocolAddress(arpPacket.getTargetProtocolAddress());
            arpReply.setSenderHardwareAddress(replyMac.toBytes());

            // Ethernet Frame.
            Ethernet ethReply = new Ethernet();
            ethReply.setSourceMACAddress(replyMac.toBytes());
            ethReply.setDestinationMACAddress(packet.getSourceMAC());
            ethReply.setEtherType(Ethernet.TYPE_ARP);
            ethReply.setVlanID(packet.getVlanID());
            ethReply.setPayload(arpReply);

            ConnectPoint targetPort = context.inPacket().receivedFrom();
            TrafficTreatment t = DefaultTrafficTreatment.builder()
                    .setOutput(targetPort.port()).build();
            OutboundPacket o = new DefaultOutboundPacket(
                    targetPort.deviceId(), t, ByteBuffer.wrap(ethReply.serialize()));
            if (log.isTraceEnabled()) {
                log.trace("Relaying ARP packet {} to {}", packet, targetPort);
            }
            packetService.emit(o);
        }
    }

    /**
     * Listener for network config events.
     */
    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                    event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                    event.configClass().equals(DhcpRelayConfig.class)) {
                updateConfig();
                log.info("Reconfigured");
            }
        }
    }

    /**
     * Internal listener for host events.
     */
    private class InternalHostListener implements HostListener {
        @Override
        public void event(HostEvent event) {
            switch (event.type()) {
            case HOST_ADDED:
            case HOST_UPDATED:
                hostUpdated(event.subject());
                break;
            case HOST_REMOVED:
                hostRemoved(event.subject());
                break;
            case HOST_MOVED:
                // XXX todo -- moving dhcp server
                break;
            default:
                break;
            }
        }
    }
}
