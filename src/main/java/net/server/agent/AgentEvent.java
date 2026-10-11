package net.server.agent;

/**
 * Game events an agent can perceive. Events are produced by lightweight hooks placed at
 * the choke points where the server already broadcasts chat / party / map updates, and are
 * enqueued on the target {@link AgentSession}.
 */
public sealed interface AgentEvent {

    /** A general (map) chat message spoken by a nearby player. */
    record MapChat(int speakerId, String speakerName, String message, boolean gmChat) implements AgentEvent {
    }

    /** A party chat message. */
    record PartyChat(int speakerId, String speakerName, String message) implements AgentEvent {
    }

    /** A whisper directed at the agent. */
    record Whisper(int speakerId, String speakerName, String message) implements AgentEvent {
    }

    /** The agent has been invited to a party. */
    record PartyInvite(int inviterId, String inviterName, int partyId) implements AgentEvent {
    }

    /** The agent's party changed (join/leave/leader change). */
    record PartyUpdate(String description) implements AgentEvent {
    }

    /** The agent's character changed map. */
    record MapChanged(int mapId) implements AgentEvent {
    }

    /** A scheduled heartbeat; lets the agent act without an external trigger. */
    record Tick() implements AgentEvent {
    }

    /** An out-of-band instruction (e.g. from a GM command). */
    record Instruction(String message) implements AgentEvent {
    }
}
