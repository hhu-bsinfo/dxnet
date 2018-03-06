/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science,
 * Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet.main.messages;

/**
 * Message types when running DXNetMain
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 21.01.2018
 */
public final class Messages {
    public static final byte DXNETMAIN_MESSAGES_TYPE = 1;

    public static final byte SUBTYPE_LOGIN_REQUEST = 0;
    public static final byte SUBTYPE_LOGIN_RESPONSE = 1;
    public static final byte SUBTYPE_START_MESSAGE = 2;
    public static final byte SUBTYPE_BENCHMARK_MESSAGE = 3;
    public static final byte SUBTYPE_BENCHMARK_REQUEST = 4;
    public static final byte SUBTYPE_BENCHMARK_RESPONSE = 5;

    /**
     * Hidden constructor
     */
    private Messages() {
    }
}
