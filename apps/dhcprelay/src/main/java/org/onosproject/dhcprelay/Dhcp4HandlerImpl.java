/*
 * Copyright 2017-present Open Networking Foundation
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
 *
 */

package org.onosproject.dhcprelay;

import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.packet.BasePacket;
import org.onlab.packet.DHCP;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.UDP;
import org.onlab.packet.VlanId;
import org.onlab.packet.dhcp.CircuitId;
import org.onlab.packet.dhcp.DhcpOption;
import org.onlab.packet.dhcp.DhcpRelayAgentOption;
import org.onosproject.dhcprelay.api.DhcpHandler;
import org.onosproject.dhcprelay.store.DhcpRecord;
import org.onosproject.dhcprelay.store.DhcpRelayStore;
import org.onosproject.net.intf.Interface;
import org.onosproject.net.intf.InterfaceService;
import org.onosproject.routeservice.Route;
import org.onosproject.routeservice.RouteStore;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.HostLocation;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.host.DefaultHostDescription;
import org.onosproject.net.host.HostDescription;
import org.onosproject.net.host.HostService;
import org.onosproject.net.host.HostStore;
import org.onosproject.net.host.InterfaceIpAddress;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.onlab.packet.DHCP.DHCPOptionCode.OptionCode_CircuitID;
import static org.onlab.packet.DHCP.DHCPOptionCode.OptionCode_END;
import static org.onlab.packet.DHCP.DHCPOptionCode.OptionCode_MessageType;
import static org.onlab.packet.MacAddress.valueOf;
import static org.onlab.packet.dhcp.DhcpRelayAgentOption.RelayAgentInfoOptions.CIRCUIT_ID;

@Component
@Service
@Property(name = "version", value = "4")
public class Dhcp4HandlerImpl implements DhcpHandler {
    private static Logger log = LoggerFactory.getLogger(Dhcp4HandlerImpl.class);

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DhcpRelayStore dhcpRelayStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostStore hostStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected RouteStore routeStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InterfaceService interfaceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    private Ip4Address dhcpServerIp = null;
    // dhcp server may be connected directly to the SDN network or
    // via an external gateway. When connected directly, the dhcpConnectPoint, dhcpConnectMac,
    // and dhcpConnectVlan refer to the server. When connected via the gateway, they refer
    // to the gateway.
    private ConnectPoint dhcpServerConnectPoint = null;
    private MacAddress dhcpConnectMac = null;
    private VlanId dhcpConnectVlan = null;
    private Ip4Address dhcpGatewayIp = null;

    @Override
    public void setDhcpServerIp(IpAddress dhcpServerIp) {
        checkNotNull(dhcpServerIp, "DHCP server IP can't be null");
        checkState(dhcpServerIp.isIp4(), "Invalid server IP for DHCPv4 relay handler");
        this.dhcpServerIp = dhcpServerIp.getIp4Address();
    }

    @Override
    public void setDhcpServerConnectPoint(ConnectPoint dhcpServerConnectPoint) {
        checkNotNull(dhcpServerConnectPoint, "Server connect point can't null");
        this.dhcpServerConnectPoint = dhcpServerConnectPoint;
    }

    @Override
    public void setDhcpConnectMac(MacAddress dhcpConnectMac) {
        this.dhcpConnectMac = dhcpConnectMac;
    }

    @Override
    public void setDhcpConnectVlan(VlanId dhcpConnectVlan) {
        this.dhcpConnectVlan = dhcpConnectVlan;
    }

    @Override
    public void setDhcpGatewayIp(IpAddress dhcpGatewayIp) {
        if (dhcpGatewayIp != null) {
            checkState(dhcpGatewayIp.isIp4(), "Invalid gateway IP for DHCPv4 relay handler");
            this.dhcpGatewayIp = dhcpGatewayIp.getIp4Address();
        } else {
            // removes gateway config
            this.dhcpGatewayIp = null;
        }
    }

    @Override
    public Optional<IpAddress> getDhcpServerIp() {
        return Optional.ofNullable(dhcpServerIp);
    }

    @Override
    public Optional<IpAddress> getDhcpGatewayIp() {
        return Optional.ofNullable(dhcpGatewayIp);
    }

    @Override
    public Optional<MacAddress> getDhcpConnectMac() {
        return Optional.ofNullable(dhcpConnectMac);
    }

    @Override
    public void processDhcpPacket(PacketContext context, BasePacket payload) {
        checkNotNull(payload, "DHCP payload can't be null");
        checkState(payload instanceof DHCP, "Payload is not a DHCP");
        DHCP dhcpPayload = (DHCP) payload;
        if (!configured()) {
            log.warn("Missing DHCP relay server config. Abort packet processing");
            return;
        }

        ConnectPoint inPort = context.inPacket().receivedFrom();
        checkNotNull(dhcpPayload, "Can't find DHCP payload");
        Ethernet packet = context.inPacket().parsed();
        DHCP.MsgType incomingPacketType = dhcpPayload.getOptions().stream()
                .filter(dhcpOption -> dhcpOption.getCode() == OptionCode_MessageType.getValue())
                .map(DhcpOption::getData)
                .map(data -> DHCP.MsgType.getType(data[0]))
                .findFirst()
                .orElse(null);
        checkNotNull(incomingPacketType, "Can't get message type from DHCP payload {}", dhcpPayload);
        switch (incomingPacketType) {
            case DHCPDISCOVER:
                // Try update host if it is directly connected.
                if (directlyConnected(dhcpPayload)) {
                    updateHost(context, dhcpPayload);
                }

                // Add the gateway IP as virtual interface IP for server to understand
                // the lease to be assigned and forward the packet to dhcp server.
                Ethernet ethernetPacketDiscover =
                        processDhcpPacketFromClient(context, packet);
                if (ethernetPacketDiscover != null) {
                    writeRequestDhcpRecord(inPort, packet, dhcpPayload);
                    handleDhcpDiscoverAndRequest(ethernetPacketDiscover);
                }
                break;
            case DHCPOFFER:
                //reply to dhcp client.
                Ethernet ethernetPacketOffer = processDhcpPacketFromServer(packet);
                if (ethernetPacketOffer != null) {
                    writeResponseDhcpRecord(ethernetPacketOffer, dhcpPayload);
                    sendResponseToClient(ethernetPacketOffer, dhcpPayload);
                }
                break;
            case DHCPREQUEST:
                // add the gateway ip as virtual interface ip for server to understand
                // the lease to be assigned and forward the packet to dhcp server.
                Ethernet ethernetPacketRequest =
                        processDhcpPacketFromClient(context, packet);
                if (ethernetPacketRequest != null) {
                    writeRequestDhcpRecord(inPort, packet, dhcpPayload);
                    handleDhcpDiscoverAndRequest(ethernetPacketRequest);
                }
                break;
            case DHCPACK:
                // reply to dhcp client.
                Ethernet ethernetPacketAck = processDhcpPacketFromServer(packet);
                if (ethernetPacketAck != null) {
                    writeResponseDhcpRecord(ethernetPacketAck, dhcpPayload);
                    handleDhcpAck(ethernetPacketAck, dhcpPayload);
                    sendResponseToClient(ethernetPacketAck, dhcpPayload);
                }
                break;
            case DHCPRELEASE:
                // TODO: release the ip address from client
                break;
            default:
                break;
        }
    }

    /**
     * Updates host to host store according to DHCP payload.
     *
     * @param context the packet context
     * @param dhcpPayload the DHCP payload
     */
    private void updateHost(PacketContext context, DHCP dhcpPayload) {
        ConnectPoint location = context.inPacket().receivedFrom();
        HostLocation hostLocation = new HostLocation(location, System.currentTimeMillis());
        MacAddress macAddress = MacAddress.valueOf(dhcpPayload.getClientHardwareAddress());
        VlanId vlanId = VlanId.vlanId(context.inPacket().parsed().getVlanID());
        HostId hostId = HostId.hostId(macAddress, vlanId);
        HostDescription desc = new DefaultHostDescription(macAddress, vlanId, hostLocation);
        hostStore.createOrUpdateHost(DhcpRelayManager.PROVIDER_ID, hostId, desc, false);
    }

    /**
     * Checks if this app has been configured.
     *
     * @return true if all information we need have been initialized
     */
    public boolean configured() {
        return dhcpServerConnectPoint != null && dhcpServerIp != null;
    }

    /**
     * Returns the first interface ip from interface.
     *
     * @param iface interface of one connect point
     * @return the first interface IP; null if not exists an IP address in
     *         these interfaces
     */
    private Ip4Address getRelayAgentIPv4Address(Interface iface) {
        checkNotNull(iface, "Interface can't be null");
        return iface.ipAddressesList().stream()
                .map(InterfaceIpAddress::ipAddress)
                .filter(IpAddress::isIp4)
                .map(IpAddress::getIp4Address)
                .findFirst()
                .orElse(null);
    }

    /**
     * Gets Interface facing to the server.
     *
     * @return the Interface facing to the server; null if not found
     */
    public Interface getServerInterface() {
        if (dhcpServerConnectPoint == null || dhcpConnectVlan == null) {
            return null;
        }
        return interfaceService.getInterfacesByPort(dhcpServerConnectPoint)
                .stream()
                .filter(iface -> iface.vlan().equals(dhcpConnectVlan) ||
                        iface.vlanUntagged().equals(dhcpConnectVlan) ||
                        iface.vlanTagged().contains(dhcpConnectVlan) ||
                        iface.vlanNative().equals(dhcpConnectVlan))
                .findFirst()
                .orElse(null);
    }

    /**
     * Build the DHCP discover/request packet with gateway IP(unicast packet).
     *
     * @param context the packet context
     * @param ethernetPacket the ethernet payload to process
     * @return processed packet
     */
    private Ethernet processDhcpPacketFromClient(PacketContext context,
                                                 Ethernet ethernetPacket) {
        Ip4Address clientInterfaceIp =
                interfaceService.getInterfacesByPort(context.inPacket().receivedFrom())
                        .stream()
                        .map(Interface::ipAddressesList)
                        .flatMap(Collection::stream)
                        .map(InterfaceIpAddress::ipAddress)
                        .filter(IpAddress::isIp4)
                        .map(IpAddress::getIp4Address)
                        .findFirst()
                        .orElse(null);
        if (clientInterfaceIp == null) {
            log.warn("Can't find interface IP for client interface for port {}",
                     context.inPacket().receivedFrom());
            return null;
        }
        Interface serverInterface = getServerInterface();
        if (serverInterface == null) {
            log.warn("Can't get server interface, ignore");
            return null;
        }
        Ip4Address relayAgentIp = getRelayAgentIPv4Address(serverInterface);
        MacAddress relayAgentMac = serverInterface.mac();
        if (relayAgentIp == null || relayAgentMac == null) {
            log.warn("No IP address for server Interface {}", serverInterface);
            return null;
        }
        if (dhcpConnectMac == null) {
            log.warn("DHCP {} not yet resolved .. Aborting DHCP "
                             + "packet processing from client on port: {}",
                     (dhcpGatewayIp == null) ? "server IP " + dhcpServerIp
                             : "gateway IP " + dhcpGatewayIp,
                     context.inPacket().receivedFrom());
            return null;
        }
        // get dhcp header.
        Ethernet etherReply = (Ethernet) ethernetPacket.clone();
        etherReply.setSourceMACAddress(relayAgentMac);
        etherReply.setDestinationMACAddress(dhcpConnectMac);
        etherReply.setVlanID(dhcpConnectVlan.toShort());
        IPv4 ipv4Packet = (IPv4) etherReply.getPayload();
        ipv4Packet.setSourceAddress(relayAgentIp.toInt());
        ipv4Packet.setDestinationAddress(dhcpServerIp.toInt());
        UDP udpPacket = (UDP) ipv4Packet.getPayload();
        DHCP dhcpPacket = (DHCP) udpPacket.getPayload();

        if (directlyConnected(dhcpPacket)) {
            ConnectPoint inPort = context.inPacket().receivedFrom();
            VlanId vlanId = VlanId.vlanId(ethernetPacket.getVlanID());
            // add connected in port and vlan
            CircuitId cid = new CircuitId(inPort.toString(), vlanId);
            byte[] circuitId = cid.serialize();
            DhcpOption circuitIdSubOpt = new DhcpOption();
            circuitIdSubOpt
                    .setCode(CIRCUIT_ID.getValue())
                    .setLength((byte) circuitId.length)
                    .setData(circuitId);

            DhcpRelayAgentOption newRelayAgentOpt = new DhcpRelayAgentOption();
            newRelayAgentOpt.setCode(OptionCode_CircuitID.getValue());
            newRelayAgentOpt.addSubOption(circuitIdSubOpt);

            // Removes END option  first
            List<DhcpOption> options = dhcpPacket.getOptions().stream()
                    .filter(opt -> opt.getCode() != OptionCode_END.getValue())
                    .collect(Collectors.toList());

            // push relay agent option
            options.add(newRelayAgentOpt);

            // make sure option 255(End) is the last option
            DhcpOption endOption = new DhcpOption();
            endOption.setCode(OptionCode_END.getValue());
            options.add(endOption);

            dhcpPacket.setOptions(options);

            // Sets giaddr to IP address from the Interface which facing to
            // DHCP client
            dhcpPacket.setGatewayIPAddress(clientInterfaceIp.toInt());
        }

        udpPacket.setPayload(dhcpPacket);
        // As a DHCP relay, the source port should be server port(67) instead
        // of client port(68)
        udpPacket.setSourcePort(UDP.DHCP_SERVER_PORT);
        udpPacket.setDestinationPort(UDP.DHCP_SERVER_PORT);
        ipv4Packet.setPayload(udpPacket);
        etherReply.setPayload(ipv4Packet);
        return etherReply;
    }

    /**
     * Writes DHCP record to the store according to the request DHCP packet (Discover, Request).
     *
     * @param location the location which DHCP packet comes from
     * @param ethernet the DHCP packet
     * @param dhcpPayload the DHCP payload
     */
    private void writeRequestDhcpRecord(ConnectPoint location,
                                        Ethernet ethernet,
                                        DHCP dhcpPayload) {
        VlanId vlanId = VlanId.vlanId(ethernet.getVlanID());
        MacAddress macAddress = MacAddress.valueOf(dhcpPayload.getClientHardwareAddress());
        HostId hostId = HostId.hostId(macAddress, vlanId);
        DhcpRecord record = dhcpRelayStore.getDhcpRecord(hostId).orElse(null);
        if (record == null) {
            record = new DhcpRecord(HostId.hostId(macAddress, vlanId));
        } else {
            record = record.clone();
        }
        record.addLocation(new HostLocation(location, System.currentTimeMillis()));
        record.ip4Status(dhcpPayload.getPacketType());
        record.setDirectlyConnected(directlyConnected(dhcpPayload));
        if (!directlyConnected(dhcpPayload)) {
            // Update gateway mac address if the host is not directly connected
            record.nextHop(ethernet.getSourceMAC());
        }
        record.updateLastSeen();
        dhcpRelayStore.updateDhcpRecord(HostId.hostId(macAddress, vlanId), record);
    }

    /**
     * Writes DHCP record to the store according to the response DHCP packet (Offer, Ack).
     *
     * @param ethernet the DHCP packet
     * @param dhcpPayload the DHCP payload
     */
    private void writeResponseDhcpRecord(Ethernet ethernet,
                                         DHCP dhcpPayload) {
        Optional<Interface> outInterface = getClientInterface(ethernet, dhcpPayload);
        if (!outInterface.isPresent()) {
            log.warn("Failed to determine where to send {}", dhcpPayload.getPacketType());
            return;
        }

        Interface outIface = outInterface.get();
        ConnectPoint location = outIface.connectPoint();
        VlanId vlanId = getVlanIdFromOption(dhcpPayload);
        if (vlanId == null) {
            vlanId = outIface.vlan();
        }
        MacAddress macAddress = MacAddress.valueOf(dhcpPayload.getClientHardwareAddress());
        HostId hostId = HostId.hostId(macAddress, vlanId);
        DhcpRecord record = dhcpRelayStore.getDhcpRecord(hostId).orElse(null);
        if (record == null) {
            record = new DhcpRecord(HostId.hostId(macAddress, vlanId));
        } else {
            record = record.clone();
        }
        record.addLocation(new HostLocation(location, System.currentTimeMillis()));
        if (dhcpPayload.getPacketType() == DHCP.MsgType.DHCPACK) {
            record.ip4Address(Ip4Address.valueOf(dhcpPayload.getYourIPAddress()));
        }
        record.ip4Status(dhcpPayload.getPacketType());
        record.setDirectlyConnected(directlyConnected(dhcpPayload));
        record.updateLastSeen();
        dhcpRelayStore.updateDhcpRecord(HostId.hostId(macAddress, vlanId), record);
    }

    /**
     * Build the DHCP offer/ack with proper client port.
     *
     * @param ethernetPacket the original packet comes from server
     * @return new packet which will send to the client
     */
    private Ethernet processDhcpPacketFromServer(Ethernet ethernetPacket) {
        // get dhcp header.
        Ethernet etherReply = (Ethernet) ethernetPacket.clone();
        IPv4 ipv4Packet = (IPv4) etherReply.getPayload();
        UDP udpPacket = (UDP) ipv4Packet.getPayload();
        DHCP dhcpPayload = (DHCP) udpPacket.getPayload();

        // determine the vlanId of the client host - note that this vlan id
        // could be different from the vlan in the packet from the server
        Interface clientInterface = getClientInterface(ethernetPacket, dhcpPayload).orElse(null);

        if (clientInterface == null) {
            log.warn("Cannot find the interface for the DHCP {}", dhcpPayload);
            return null;
        }
        VlanId vlanId;
        if (clientInterface.vlanTagged().isEmpty()) {
            vlanId = clientInterface.vlan();
        } else {
            // might be multiple vlan in same interface
            vlanId = getVlanIdFromOption(dhcpPayload);
        }
        if (vlanId == null) {
            vlanId = VlanId.NONE;
        }
        etherReply.setVlanID(vlanId.toShort());
        etherReply.setSourceMACAddress(clientInterface.mac());

        if (!directlyConnected(dhcpPayload)) {
            // if client is indirectly connected, try use next hop mac address
            MacAddress macAddress = MacAddress.valueOf(dhcpPayload.getClientHardwareAddress());
            HostId hostId = HostId.hostId(macAddress, vlanId);
            DhcpRecord record = dhcpRelayStore.getDhcpRecord(hostId).orElse(null);
            if (record != null) {
                // if next hop can be found, use mac address of next hop
                record.nextHop().ifPresent(etherReply::setDestinationMACAddress);
            } else {
                // otherwise, discard the packet
                log.warn("Can't find record for host id {}, discard packet", hostId);
                return null;
            }

        }

        // we leave the srcMac from the original packet
        // figure out the relay agent IP corresponding to the original request
        Ip4Address relayAgentIP = getRelayAgentIPv4Address(clientInterface);
        if (relayAgentIP == null) {
            log.warn("Cannot determine relay agent interface Ipv4 addr for host {}/{}. "
                             + "Aborting relay for dhcp packet from server {}",
                     etherReply.getDestinationMAC(), clientInterface.vlan(),
                     ethernetPacket);
            return null;
        }
        // SRC_IP: relay agent IP
        // DST_IP: offered IP
        ipv4Packet.setSourceAddress(relayAgentIP.toInt());
        ipv4Packet.setDestinationAddress(dhcpPayload.getYourIPAddress());
        udpPacket.setSourcePort(UDP.DHCP_SERVER_PORT);
        if (directlyConnected(dhcpPayload)) {
            udpPacket.setDestinationPort(UDP.DHCP_CLIENT_PORT);
        } else {
            // forward to another dhcp relay
            udpPacket.setDestinationPort(UDP.DHCP_SERVER_PORT);
        }

        udpPacket.setPayload(dhcpPayload);
        ipv4Packet.setPayload(udpPacket);
        etherReply.setPayload(ipv4Packet);
        return etherReply;
    }

    /**
     * Extracts VLAN ID from relay agent option.
     *
     * @param dhcpPayload the DHCP payload
     * @return VLAN ID from DHCP payload; null if not exists
     */
    private VlanId getVlanIdFromOption(DHCP dhcpPayload) {
        DhcpRelayAgentOption option = (DhcpRelayAgentOption) dhcpPayload.getOption(OptionCode_CircuitID);
        if (option == null) {
            return null;
        }
        DhcpOption circuitIdSubOption = option.getSubOption(CIRCUIT_ID.getValue());
        if (circuitIdSubOption == null) {
            return null;
        }
        try {
            CircuitId circuitId = CircuitId.deserialize(circuitIdSubOption.getData());
            return circuitId.vlanId();
        } catch (IllegalArgumentException e) {
            // can't deserialize the circuit ID
            return null;
        }
    }

    /**
     * Removes DHCP relay agent information option (option 82) from DHCP payload.
     * Also reset giaddr to 0
     *
     * @param ethPacket the Ethernet packet to be processed
     * @return Ethernet packet processed
     */
    private Ethernet removeRelayAgentOption(Ethernet ethPacket) {
        Ethernet ethernet = (Ethernet) ethPacket.clone();
        IPv4 ipv4 = (IPv4) ethernet.getPayload();
        UDP udp = (UDP) ipv4.getPayload();
        DHCP dhcpPayload = (DHCP) udp.getPayload();

        // removes relay agent information option
        List<DhcpOption> options = dhcpPayload.getOptions();
        options = options.stream()
                .filter(option -> option.getCode() != OptionCode_CircuitID.getValue())
                .collect(Collectors.toList());
        dhcpPayload.setOptions(options);
        dhcpPayload.setGatewayIPAddress(0);

        udp.setPayload(dhcpPayload);
        ipv4.setPayload(udp);
        ethernet.setPayload(ipv4);
        return ethernet;
    }


    /**
     * Check if the host is directly connected to the network or not.
     *
     * @param dhcpPayload the dhcp payload
     * @return true if the host is directly connected to the network; false otherwise
     */
    private boolean directlyConnected(DHCP dhcpPayload) {
        DhcpOption relayAgentOption = dhcpPayload.getOption(OptionCode_CircuitID);

        // Doesn't contains relay option
        if (relayAgentOption == null) {
            return true;
        }

        IpAddress gatewayIp = IpAddress.valueOf(dhcpPayload.getGatewayIPAddress());
        Set<Interface> gatewayInterfaces = interfaceService.getInterfacesByIp(gatewayIp);

        // Contains relay option, and added by ONOS
        if (!gatewayInterfaces.isEmpty()) {
            return true;
        }

        // Relay option added by other relay agent
        return false;
    }


    /**
     * Send the DHCP ack to the requester host.
     * Modify Host or Route store according to the type of DHCP.
     *
     * @param ethernetPacketAck the packet
     * @param dhcpPayload the DHCP data
     */
    private void handleDhcpAck(Ethernet ethernetPacketAck, DHCP dhcpPayload) {
        Optional<Interface> outInterface = getClientInterface(ethernetPacketAck, dhcpPayload);
        if (!outInterface.isPresent()) {
            log.warn("Can't find output interface for dhcp: {}", dhcpPayload);
            return;
        }

        Interface outIface = outInterface.get();
        HostLocation hostLocation = new HostLocation(outIface.connectPoint(), System.currentTimeMillis());
        MacAddress macAddress = MacAddress.valueOf(dhcpPayload.getClientHardwareAddress());
        VlanId vlanId = getVlanIdFromOption(dhcpPayload);
        if (vlanId == null) {
            vlanId = outIface.vlan();
        }
        HostId hostId = HostId.hostId(macAddress, vlanId);
        Ip4Address ip = Ip4Address.valueOf(dhcpPayload.getYourIPAddress());

        if (directlyConnected(dhcpPayload)) {
            // Add to host store if it connect to network directly
            Set<IpAddress> ips = Sets.newHashSet(ip);
            HostDescription desc = new DefaultHostDescription(macAddress, vlanId,
                                                              hostLocation, ips);

            // Replace the ip when dhcp server give the host new ip address
            hostStore.createOrUpdateHost(DhcpRelayManager.PROVIDER_ID, hostId, desc, false);
        } else {
            // Add to route store if it does not connect to network directly
            // Get gateway host IP according to host mac address
            // TODO: remove relay store here
            DhcpRecord record = dhcpRelayStore.getDhcpRecord(hostId).orElse(null);

            if (record == null) {
                log.warn("Can't find DHCP record of host {}", hostId);
                return;
            }

            MacAddress gwMac = record.nextHop().orElse(null);
            if (gwMac == null) {
                log.warn("Can't find gateway mac address from record {}", record);
                return;
            }

            HostId gwHostId = HostId.hostId(gwMac, record.vlanId());
            Host gwHost = hostService.getHost(gwHostId);

            if (gwHost == null) {
                log.warn("Can't find gateway host {}", gwHostId);
                return;
            }

            Ip4Address nextHopIp = gwHost.ipAddresses()
                    .stream()
                    .filter(IpAddress::isIp4)
                    .map(IpAddress::getIp4Address)
                    .findFirst()
                    .orElse(null);

            if (nextHopIp == null) {
                log.warn("Can't find IP address of gateway {}", gwHost);
                return;
            }

            Route route = new Route(Route.Source.STATIC, ip.toIpPrefix(), nextHopIp);
            routeStore.updateRoute(route);
        }
    }

    /**
     * forward the packet to ConnectPoint where the DHCP server is attached.
     *
     * @param packet the packet
     */
    private void handleDhcpDiscoverAndRequest(Ethernet packet) {
        // send packet to dhcp server connect point.
        if (dhcpServerConnectPoint != null) {
            TrafficTreatment t = DefaultTrafficTreatment.builder()
                    .setOutput(dhcpServerConnectPoint.port()).build();
            OutboundPacket o = new DefaultOutboundPacket(
                    dhcpServerConnectPoint.deviceId(), t, ByteBuffer.wrap(packet.serialize()));
            if (log.isTraceEnabled()) {
                log.trace("Relaying packet to dhcp server {}", packet);
            }
            packetService.emit(o);
        } else {
            log.warn("Can't find DHCP server connect point, abort.");
        }
    }


    /**
     * Gets output interface of a dhcp packet.
     * If option 82 exists in the dhcp packet and the option was sent by
     * ONOS (gateway address exists in ONOS interfaces), use the connect
     * point and vlan id from circuit id; otherwise, find host by destination
     * address and use vlan id from sender (dhcp server).
     *
     * @param ethPacket the ethernet packet
     * @param dhcpPayload the dhcp packet
     * @return an interface represent the output port and vlan; empty value
     *         if the host or circuit id not found
     */
    private Optional<Interface> getClientInterface(Ethernet ethPacket, DHCP dhcpPayload) {
        VlanId originalPacketVlanId = VlanId.vlanId(ethPacket.getVlanID());
        IpAddress gatewayIpAddress = Ip4Address.valueOf(dhcpPayload.getGatewayIPAddress());

        // get all possible interfaces for client
        Set<Interface> clientInterfaces = interfaceService.getInterfacesByIp(gatewayIpAddress);
        DhcpRelayAgentOption option = (DhcpRelayAgentOption) dhcpPayload.getOption(OptionCode_CircuitID);

        // Sent by ONOS, and contains circuit id
        if (!clientInterfaces.isEmpty() && option != null) {
            DhcpOption circuitIdSubOption = option.getSubOption(CIRCUIT_ID.getValue());
            try {
                CircuitId circuitId = CircuitId.deserialize(circuitIdSubOption.getData());
                ConnectPoint connectPoint = ConnectPoint.deviceConnectPoint(circuitId.connectPoint());
                VlanId vlanId = circuitId.vlanId();
                return clientInterfaces.stream()
                        .filter(iface -> iface.vlanUntagged().equals(vlanId) ||
                                iface.vlan().equals(vlanId) ||
                                iface.vlanNative().equals(vlanId) ||
                                iface.vlanTagged().contains(vlanId))
                        .filter(iface -> iface.connectPoint().equals(connectPoint))
                        .findFirst();
            } catch (IllegalArgumentException ex) {
                // invalid circuit format, didn't sent by ONOS
                log.debug("Invalid circuit {}, use information from dhcp payload",
                          circuitIdSubOption.getData());
            }
        }

        // Use Vlan Id from DHCP server if DHCP relay circuit id was not
        // sent by ONOS or circuit Id can't be parsed
        // TODO: remove relay store from this method
        MacAddress dstMac = valueOf(dhcpPayload.getClientHardwareAddress());
        Optional<DhcpRecord> dhcpRecord = dhcpRelayStore.getDhcpRecord(HostId.hostId(dstMac, originalPacketVlanId));
        ConnectPoint clientConnectPoint = dhcpRecord
                .map(DhcpRecord::locations)
                .orElse(Collections.emptySet())
                .stream()
                .reduce((hl1, hl2) -> {
                    // find latest host connect point
                    if (hl1 == null || hl2 == null) {
                        return hl1 == null ? hl2 : hl1;
                    }
                    return hl1.time() > hl2.time() ? hl1 : hl2;
                })
                .orElse(null);

        if (clientConnectPoint != null) {
            return interfaceService.getInterfacesByPort(clientConnectPoint)
                    .stream()
                    .filter(iface -> iface.vlan().equals(originalPacketVlanId) ||
                            iface.vlanUntagged().equals(originalPacketVlanId))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * Send the response DHCP to the requester host.
     *
     * @param ethPacket the packet
     * @param dhcpPayload the DHCP data
     */
    private void sendResponseToClient(Ethernet ethPacket, DHCP dhcpPayload) {
        Optional<Interface> outInterface = getClientInterface(ethPacket, dhcpPayload);
        if (directlyConnected(dhcpPayload)) {
            ethPacket = removeRelayAgentOption(ethPacket);
        }
        if (!outInterface.isPresent()) {
            log.warn("Can't find output interface for client, ignore");
            return;
        }
        Interface outIface = outInterface.get();
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outIface.connectPoint().port())
                .build();
        OutboundPacket o = new DefaultOutboundPacket(
                outIface.connectPoint().deviceId(),
                treatment,
                ByteBuffer.wrap(ethPacket.serialize()));
        if (log.isTraceEnabled()) {
            log.trace("Relaying packet to DHCP client {} via {}, vlan {}",
                      ethPacket,
                      outIface.connectPoint(),
                      outIface.vlan());
        }
        packetService.emit(o);
    }
}
