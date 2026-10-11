/* Persistent memory for in-process LLM agent players.
   Manually run this script once in your SQL client of choice. */
CREATE TABLE IF NOT EXISTS `agent_memory` (
  `characterid` int(11) NOT NULL,
  `facts` text,
  `dialogue` text,
  `updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`characterid`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
