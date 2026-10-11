package net.server.agent;

import client.Client;
import net.PacketProcessor;
import net.packet.Packet;

import java.util.function.Consumer;

/**
 * An in-process, connection-less {@link Client} used by LLM agents.
 * <p>
 * Agents have no Netty channel. Outbound packets (everything the server would send to a
 * real client) are captured through {@link #writePacket(Packet)} instead of being written
 * to a socket, so a {@link AgentSession} can observe what its character is told.
 */
public class AgentClient extends Client {
    private volatile Consumer<Packet> packetListener;

    public AgentClient(long sessionId, PacketProcessor packetProcessor, int world, int channel) {
        super(Type.CHANNEL, sessionId, "agent", packetProcessor, world, channel);
    }

    /**
     * Registers the sink that receives every outbound packet for this agent.
     * May be {@code null}, in which case outbound packets are silently discarded.
     */
    public void setPacketListener(Consumer<Packet> packetListener) {
        this.packetListener = packetListener;
    }

    @Override
    protected void writePacket(Packet packet) {
        final Consumer<Packet> listener = this.packetListener;
        if (listener != null) {
            listener.accept(packet);
        }
    }
}
