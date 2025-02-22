/**
 * Copyright (C) 2024 Bonitasoft S.A.
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
package org.bonitasoft.engine.execution;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.bonitasoft.engine.core.process.instance.api.ProcessInstanceService;
import org.bonitasoft.engine.core.process.instance.api.exceptions.SProcessInstanceCreationException;
import org.bonitasoft.engine.core.process.instance.model.SProcessInstance;
import org.bonitasoft.engine.platform.PlatformRetriever;
import org.bonitasoft.engine.platform.exception.SPlatformNotFoundException;
import org.bonitasoft.engine.platform.exception.SPlatformUpdateException;
import org.bonitasoft.engine.service.platform.PlatformInformationService;
import org.bonitasoft.engine.transaction.TransactionService;
import org.bonitasoft.platform.setup.SimpleEncryptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnSingleCandidate(ProcessStarterVerifier.class)
@Slf4j
public class ProcessStarterVerifierImpl implements ProcessStarterVerifier {

    public static final int LIMIT = 150;
    protected static final int PERIOD_IN_DAYS = 30;
    protected static final long PERIOD_IN_MILLIS = PERIOD_IN_DAYS * 24L * 60L * 60L * 1000L;
    protected static final List<Integer> THRESHOLDS_IN_PERCENT = List.of(80, 90);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PlatformRetriever platformRetriever;
    private final PlatformInformationService platformInformationService;
    private final TransactionService transactionService;
    private final ProcessInstanceService processInstanceService;

    private final List<Long> counters = Collections.synchronizedList(new ArrayList<>());

    @Autowired
    public ProcessStarterVerifierImpl(PlatformRetriever platformRetriever,
            PlatformInformationService platformInformationService,
            TransactionService transactionService,
            ProcessInstanceService processInstanceService) throws Exception {
        this.platformRetriever = platformRetriever;
        this.platformInformationService = platformInformationService;
        this.transactionService = transactionService;
        this.processInstanceService = processInstanceService;
        counters.addAll(setupCounters(transactionService));
        // clean up old values:
        final long oldestValidDate = currentTimeMillis() - PERIOD_IN_MILLIS;
        cleanupOldValues(oldestValidDate);
        // then check database integrity:
        verifyCountersCoherence(counters, oldestValidDate);
    }

    List<Long> setupCounters(TransactionService transactionService) throws Exception {
        return transactionService.executeInTransaction(this::readCounters);
    }

    protected List<Long> getCounters() {
        return Collections.unmodifiableList(counters);
    }

    protected void addCounter(long counter) {
        synchronized (counters) {
            counters.add(counter);
        }
    }

    @Override
    public void verify(SProcessInstance processInstance) throws SProcessInstanceCreationException {
        log.debug("Verifying the possibility to create process instance {}", processInstance.getId());
        final long processStartDate = processInstance.getStartDate();
        cleanupOldValues(processStartDate - PERIOD_IN_MILLIS);
        log.debug("Found {} cases already started in the last {} days", counters.size(), PERIOD_IN_DAYS);

        if (counters.size() >= LIMIT) {
            var nextResetTimestamp = getNextResetTimestamp(counters);
            final String nextValidTime = getStringRepresentation(nextResetTimestamp);
            throw new SProcessInstanceCreationException(
                    format("Process start limit (%s cases during last %s days) reached. You are not allowed to start a new process until %s.",
                            LIMIT, PERIOD_IN_DAYS, nextValidTime),
                    nextResetTimestamp);
        }
        try {
            synchronized (counters) {
                counters.add(processStartDate);
            }
            final String information = encryptDataBeforeSendingToDatabase(counters);
            // store in database:
            storeNewValueInDatabase(information);
            logCaseLimitProgressIfThresholdReached();
        } catch (IOException | SPlatformNotFoundException | SPlatformUpdateException e) {
            log.trace(e.getMessage(), e);
            throw new SProcessInstanceCreationException(
                    format("Unable to start the process instance %s", processInstance.getId()), e);
        }
    }

    void cleanupOldValues(long olderThanInMilliseconds) {
        log.trace("Cleaning up old values for the last {} days", PERIOD_IN_DAYS);
        synchronized (counters) {
            counters.removeIf(timestamp -> timestamp < olderThanInMilliseconds);
        }
    }

    void storeNewValueInDatabase(String information) throws SPlatformUpdateException, SPlatformNotFoundException {
        platformInformationService.updatePlatformInfo(platformRetriever.getPlatform(), information);
    }

    List<Long> readCounters() {
        try {
            String information = platformRetriever.getPlatform().getInformation();
            if (information == null || information.isBlank()) {
                throw new IllegalStateException("Invalid database. Please reset it and restart.");
            }
            return decryptDataFromDatabase(information);
        } catch (SPlatformNotFoundException | IOException e) {
            throw new IllegalStateException("Cannot read from database table 'platform'", e);
        }
    }

    @Override
    public long getCurrentNumberOfStartedProcessInstances() {
        return counters.size();
    }

    String encryptDataBeforeSendingToDatabase(List<Long> counters) throws IOException {
        return encrypt(OBJECT_MAPPER.writeValueAsBytes(counters));
    }

    List<Long> decryptDataFromDatabase(String information) throws IOException {
        return OBJECT_MAPPER.readValue(decrypt(information), new TypeReference<>() {
        });
    }

    private static String encrypt(byte[] data) {
        try {
            return SimpleEncryptor.encrypt(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot cipher information", e);
        }
    }

    private static byte[] decrypt(String information) {
        try {
            return SimpleEncryptor.decrypt(information);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decipher information", e);
        }
    }

    List<Long> fetchLastArchivedProcessInstanceStartDates(Long oldestValidDate) throws Exception {
        final List<Long> startDates = transactionService.executeInTransaction(
                () -> processInstanceService.getLastArchivedProcessInstanceStartDates(oldestValidDate));
        // print the start dates to the log:
        log.debug("Last archived process instance start dates: {}", startDates);
        return startDates;
    }

    public void verifyCountersCoherence(List<Long> counters, Long oldestValidDate) throws Exception {
        final List<Long> lastArchivedProcessInstanceStartDates = fetchLastArchivedProcessInstanceStartDates(
                oldestValidDate);
        for (Long startDate : lastArchivedProcessInstanceStartDates) {
            if (!counters.contains(startDate)) {
                throw new IllegalStateException("Invalid database. Please reset it and restart.");
            }
        }
    }

    void logCaseLimitProgressIfThresholdReached() {
        var percentBeforeThisNewCase = (float) ((getCounters().size() - 1) * 100) / LIMIT;
        var percentWithThisNewCase = (float) ((getCounters().size()) * 100) / LIMIT;
        for (Integer threshold : THRESHOLDS_IN_PERCENT) {
            if (percentBeforeThisNewCase < threshold && percentWithThisNewCase >= threshold) {
                log.warn("You have started {}% of your allowed cases."
                        + "If you need more volume, please consider subscribing to an Enterprise edition.",
                        threshold);
            }
        }
    }

    /**
     * Returns a timestamp to a human-readable format
     */
    private String getStringRepresentation(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    private long getNextResetTimestamp(List<Long> timestamps) {
        return Collections.min(timestamps) + PERIOD_IN_MILLIS;
    }

}
