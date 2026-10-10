/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package client.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the DB "landmine": an EQUIP row with no companion {@code inventoryequipment}
 * row is loaded through {@code inventoryitems LEFT JOIN inventoryequipment}, so the
 * joined {@code ringid} column comes back as SQL NULL. {@link java.sql.ResultSet#getInt}
 * coerces that NULL to 0, and 0 is read downstream as "has a ring" (getRingId() > -1)
 * which makes {@code Ring.loadFromDb(0)} return null and NPE the login path.
 *
 * <p>{@link ItemFactory#noRingIfNull(int, boolean)} must normalise a SQL NULL back to
 * the -1 "no ring" sentinel (the value {@link Equip} already defaults to), while leaving
 * genuine ring/cashItem ids alone. The helper is tested directly rather than through
 * {@code loadEquipFromResultSet}: constructing an {@link Equip} forces
 * {@code server.ItemInformationProvider} to initialise, which needs the WZ data and DB
 * config, so a ResultSet-level test cannot run hermetically here.
 */
class ItemFactoryLoadEquipTest {

    @Test
    void nullRingIdFromAMissingEquipmentRowBecomesTheNoRingSentinel() {
        // The landmine: getInt returned 0 *because* the column was SQL NULL.
        assertEquals(-1, ItemFactory.noRingIfNull(0, true),
                "a NULL ringid must collapse to -1, not stay 0 (0 is treated as 'has a ring')");
    }

    @Test
    void aRealRingIdIsKept() {
        // wasNull() is false because ringid held a real value, not a JOIN-miss NULL.
        assertEquals(12345, ItemFactory.noRingIfNull(12345, false),
                "a genuine ring id must survive untouched");
    }

    @Test
    void anExplicitNoRingSentinelIsKept() {
        // Most rows store the -1 default literally; that is not a NULL and stays -1.
        assertEquals(-1, ItemFactory.noRingIfNull(-1, false));
    }
}
