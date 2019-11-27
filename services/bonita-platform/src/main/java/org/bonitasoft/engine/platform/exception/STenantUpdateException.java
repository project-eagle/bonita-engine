/**
 * Copyright (C) 2019 Bonitasoft S.A.
 * Bonitasoft, 32 rue Gustave Eiffel - 38000 Grenoble
 * This library is free software; you can redistribute it and/or modify it under the terms
 * of the GNU Lesser General Public License as published by the Free Software Foundation
 * version 2.1 of the License.
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License along with this
 * program; if not, write to the Free Software Foundation, Inc., 51 Franklin Street, Fifth
 * Floor, Boston, MA 02110-1301, USA.
 **/
package org.bonitasoft.engine.platform.exception;

import org.bonitasoft.engine.commons.exceptions.SBonitaException;

/**
 * Exception related to the platform module
 * 
 * @author Charles Souillard
 */
public class STenantUpdateException extends SBonitaException {

    private static final long serialVersionUID = 7615655279956204016L;

    public STenantUpdateException(final String message) {
        super(message);
    }

    public STenantUpdateException(final Throwable e) {
        super(e);
    }

    public STenantUpdateException(final String message, final Throwable e) {
        super(message, e);
    }

}
