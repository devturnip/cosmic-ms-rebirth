-- Players who have opted out of AI-agent interaction (greetings, party-search invites,
-- whispers/party invites). Toggled by the player-facing /agentoptout command.
CREATE TABLE IF NOT EXISTS `agent_optout` (
  `characterid` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
