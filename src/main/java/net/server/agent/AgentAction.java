package net.server.agent;

/**
 * A high-level action an agent wants to perform. Actions are executed by
 * {@link AgentActionDispatcher}, which translates them into the same inbound packets (or
 * server-side calls) a real client would produce, so normal validation still applies.
 */
public sealed interface AgentAction {

    /** Speak in general (map) chat. */
    record Say(String message) implements AgentAction {
    }

    /** Speak in party chat. */
    record PartySay(String message) implements AgentAction {
    }

    /** Whisper another player. */
    record Whisper(String targetName, String message) implements AgentAction {
    }

    /** Invite a nearby player to the agent's party. */
    record InviteToParty(String targetName) implements AgentAction {
    }

    /** Accept a pending party invitation. */
    record AcceptPartyInvite(int partyId) implements AgentAction {
    }

    /** Deny a pending party invitation. */
    record DenyPartyInvite(String inviterName) implements AgentAction {
    }

    /** Leave the current party. */
    record LeaveParty() implements AgentAction {
    }

    /** Persist a durable memory fact. */
    record Remember(String fact) implements AgentAction {
    }

    /** Do nothing this cycle. */
    record Idle() implements AgentAction {
    }
}
