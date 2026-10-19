package net.server.agent.harness;

import com.fasterxml.jackson.databind.JsonNode;
import net.server.agent.AgentAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses harness MCP action payloads ({@code [{"type":"Say","message":"hi"}, ...]}) into
 * {@link AgentAction}s. Unknown types and bad payloads raise {@link IllegalArgumentException}
 * with a message the MCP layer forwards to the testing agent.
 */
public final class HarnessActions {
    private HarnessActions() {
    }

    public static List<AgentAction> parse(JsonNode array) {
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("'actions' must be a JSON array");
        }
        List<AgentAction> actions = new ArrayList<>();
        for (JsonNode node : array) {
            actions.add(parseOne(node));
        }
        return actions;
    }

    private static AgentAction parseOne(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("each action must be a JSON object");
        }
        String type = text(node, "type");
        if (type == null) {
            throw new IllegalArgumentException("action object missing 'type' field");
        }
        return switch (type.strip().toUpperCase(Locale.ROOT)) {
            case "SAY" -> new AgentAction.Say(text(node, "message"));
            case "PARTYSAY" -> new AgentAction.PartySay(text(node, "message"));
            case "WHISPER" -> new AgentAction.Whisper(text(node, "targetName"), text(node, "message"));
            case "INVITETOPARTY" -> new AgentAction.InviteToParty(text(node, "targetName"));
            case "ACCEPTPARTYINVITE" -> new AgentAction.AcceptPartyInvite(intField(node, "partyId"));
            case "DENYPARTYINVITE" -> new AgentAction.DenyPartyInvite(text(node, "inviterName"));
            case "LEAVEPARTY" -> new AgentAction.LeaveParty();
            case "MOVETO" -> new AgentAction.MoveTo(intField(node, "x"), intField(node, "y"));
            case "FOLLOW" -> new AgentAction.Follow(text(node, "targetName"));
            case "STOPFOLLOWING" -> new AgentAction.StopFollowing();
            case "PICKUPITEM" -> new AgentAction.PickupItem(intField(node, "objectId"));
            case "USEITEM" -> new AgentAction.UseItem(intField(node, "itemId"));
            case "RESPAWN" -> new AgentAction.Respawn();
            case "ATTACK" -> new AgentAction.Attack(intField(node, "targetObjectId"));
            case "SKILLATTACK" -> new AgentAction.SkillAttack(intField(node, "targetObjectId"), intField(node, "skillId"));
            case "SKILLATTACKMANY" -> {
                JsonNode targets = node.get("targetObjectIds");
                if (targets == null || !targets.isArray()) {
                    throw new IllegalArgumentException("SkillAttackMany requires 'targetObjectIds' array");
                }
                List<Integer> ids = new ArrayList<>();
                targets.forEach(t -> ids.add(t.asInt()));
                yield new AgentAction.SkillAttackMany(ids, intField(node, "skillId"));
            }
            case "RECHARGEAMMO" -> new AgentAction.RechargeAmmo();
            case "CASTBUFF" -> new AgentAction.CastBuff(intField(node, "skillId"));
            case "CASTHEAL" -> new AgentAction.CastHeal(intField(node, "skillId"));
            case "REMEMBER" -> new AgentAction.Remember(text(node, "fact"));
            case "NPCTALK" -> new AgentAction.NpcTalk(intField(node, "npcId"));
            case "NPCSELECT" -> new AgentAction.NpcSelect(intField(node, "selection"));
            case "NPCRESPONDTEXT" -> new AgentAction.NpcRespondText(text(node, "text"));
            case "NPCENDDIALOG" -> new AgentAction.NpcEndDialog();
            case "QUESTSTART" -> new AgentAction.QuestStart(intField(node, "questId"), intField(node, "npcId"));
            case "QUESTCOMPLETE" -> new AgentAction.QuestComplete(intField(node, "questId"), intField(node, "npcId"));
            case "QUESTFORFEIT" -> new AgentAction.QuestForfeit(intField(node, "questId"));
            case "ADDBUDDY" -> new AgentAction.AddBuddy(text(node, "name"));
            case "ENTERCASHSHOP" -> new AgentAction.EnterCashShop();
            case "CASHBUY" -> new AgentAction.CashBuy(intField(node, "useNX"), intField(node, "snCS"));
            case "TRADEINVITE" -> new AgentAction.TradeInvite(text(node, "targetName"));
            case "TRADEACCEPT" -> new AgentAction.TradeAccept();
            case "TRADEPUTITEM" -> new AgentAction.TradePutItem(text(node, "inventoryType"),
                    (short) intField(node, "slot"), (short) intField(node, "quantity"), (byte) intField(node, "targetSlot"));
            case "TRADEPUTMESOS" -> new AgentAction.TradePutMesos(intField(node, "amount"));
            case "TRADECONFIRM" -> new AgentAction.TradeConfirm();
            case "STORAGESTORE" -> new AgentAction.StorageStore((short) intField(node, "slot"),
                    intField(node, "itemId"), (short) intField(node, "quantity"));
            case "STORAGETAKEOUT" -> new AgentAction.StorageTakeOut(text(node, "inventoryType"),
                    (byte) intField(node, "slot"));
            case "STORAGEMESOS" -> new AgentAction.StorageMesos(intField(node, "amount"));
            case "STORAGECLOSE" -> new AgentAction.StorageClose();
            case "DUEYSEND" -> new AgentAction.DueySend(text(node, "inventoryType"),
                    (short) intField(node, "slot"), (short) intField(node, "quantity"),
                    intField(node, "mesos"), text(node, "recipient"), text(node, "message"));
            case "DUEYACTION" -> new AgentAction.DueyAction(intField(node, "operation"),
                    intField(node, "packageId", 0));
            case "IDLE" -> new AgentAction.Idle();
            default -> throw new IllegalArgumentException("Unknown action type '" + type + "'");
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static int intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("action '" + node.path("type").asText() + "' requires integer '" + field + "'");
        }
        return value.asInt();
    }

    private static int intField(JsonNode node, String field, int fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asInt();
    }
}
