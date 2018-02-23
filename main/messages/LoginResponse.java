/*
 * Copyright (C) 2018 Heinrich-Heine-Universitaet Duesseldorf, Institute of Computer Science, Department Operating Systems
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package de.hhu.bsinfo.dxnet.main.messages;

import de.hhu.bsinfo.dxnet.core.Response;

/**
 * Response for the login request
 *
 * @author Stefan Nothaas, stefan.nothaas@hhu.de, 21.01.2018
 */
public class LoginResponse extends Response {

    /**
     * Creates an instance of LoginResponse.
     */
    public LoginResponse() {
        super();
    }

    /**
     * Creates an instance of LoginResponse
     *
     * @param p_request
     *         the LoginRequest
     */
    public LoginResponse(final LoginRequest p_request) {
        super(p_request, Messages.SUBTYPE_LOGIN_RESPONSE);
    }

}
