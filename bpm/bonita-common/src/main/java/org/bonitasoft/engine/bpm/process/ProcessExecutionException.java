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
package org.bonitasoft.engine.bpm.process;

import lombok.Getter;
import org.bonitasoft.engine.exception.ExecutionException;

/**
 * Thrown when a process instance fails when it's starting.
 *
 * @author Frédéric Bouquet
 * @author Celine Souchet
 * @version 6.3.5
 * @since 6.0.0
 */
public class ProcessExecutionException extends ExecutionException {

    private static final long serialVersionUID = 4412292065541283593L;
    @Getter
    private long retryAfter = -1L;

    /**
     * Constructs a new exception with the specified detail cause.
     *
     * @param cause
     *        The cause (which is saved for later retrieval by the {@link Throwable#getCause()} method). (A null value
     *        is permitted, and indicates that the
     *        cause is nonexistent or unknown.)
     */
    public ProcessExecutionException(Throwable cause) {
        super(cause);
    }

    public ProcessExecutionException(Throwable cause, long retryAfter) {
        super(cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Constructs a new exception with the specified detail message.
     *
     * @param message
     *        The detail message (which is saved for later retrieval by the {@link Throwable#getMessage()} method).
     */
    public ProcessExecutionException(String message) {
        super(message);
    }
}
