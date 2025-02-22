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
package org.bonitasoft.platform.setup;

import static org.apache.commons.io.FilenameUtils.separatorsToSystem;
import static org.assertj.core.api.Assertions.*;
import static org.bonitasoft.platform.setup.PlatformSetup.BONITA_DB_VENDOR_PROPERTY;
import static org.bonitasoft.platform.setup.PlatformSetup.BONITA_SETUP_FOLDER;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

import javax.sql.DataSource;

import org.bonitasoft.platform.configuration.ConfigurationService;
import org.bonitasoft.platform.configuration.model.BonitaConfiguration;
import org.bonitasoft.platform.configuration.model.LightBonitaConfiguration;
import org.bonitasoft.platform.database.DatabaseVendor;
import org.bonitasoft.platform.exception.PlatformException;
import org.bonitasoft.platform.util.ConfigurationFolderUtil;
import org.bonitasoft.platform.version.VersionService;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Baptiste Mesta
 */
@RunWith(MockitoJUnitRunner.class)
public class PlatformSetupTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private DatabaseMetaData metaData;
    @Mock
    private ScriptExecutor scriptExecutor;
    @Mock
    private ConfigurationService configurationService;
    @Mock
    private VersionService versionService;
    @InjectMocks
    private PlatformSetup platformSetup;

    private final ConfigurationFolderUtil configurationFolderUtil = new ConfigurationFolderUtil();

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    @Rule
    public final TestRule restoreSystemProperties = new RestoreSystemProperties();

    @Before
    public void before() throws Exception {
        ReflectionTestUtils.setField(platformSetup, "dbVendor", DatabaseVendor.H2.getValue());
        doReturn(connection).when(dataSource).getConnection();
        doReturn(metaData).when(connection).getMetaData();
    }

    @Test
    public void should_not_check_license_if_platform_already_init() throws Exception {
        final Path setupFolder = temporaryFolder.newFolder().toPath();
        System.setProperty(BONITA_SETUP_FOLDER, setupFolder.toString());
        final Path platformConf = configurationFolderUtil.buildPlatformConfFolder(setupFolder);
        final Path licenseFolder = platformConf.resolve("licenses");
        Files.createDirectories(licenseFolder);
        doReturn(true).when(scriptExecutor).isPlatformAlreadyCreated();

        //no exception
        assertThatNoException().isThrownBy(platformSetup::init);
    }

    @Test
    public void should_fail_if_init_with_no_license() throws Exception {
        final Path setupFolder = temporaryFolder.newFolder().toPath();
        System.setProperty(BONITA_SETUP_FOLDER, setupFolder.toString());
        final Path platformConf = configurationFolderUtil.buildPlatformConfFolder(setupFolder);
        final Path licenseFolder = platformConf.resolve("licenses");
        Files.createDirectories(licenseFolder);

        assertThatExceptionOfType(PlatformException.class)
                .isThrownBy(platformSetup::init)
                .withMessageStartingWith("No license (.lic file) found.");
    }

    @Test
    public void should_fail_if_init_with_incorrect_database_vendor() {
        //given
        String dbVendor = "foobar";
        ReflectionTestUtils.setField(platformSetup, "dbVendor", dbVendor);

        //when - then
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(platformSetup::init)
                .withMessage("Unknown database vendor: %s", dbVendor);
    }

    @Test
    public void should_fail_if_init_with_unsupported_database_vendor() {
        //given
        DatabaseVendor dbVendor = DatabaseVendor.ORACLE;
        ReflectionTestUtils.setField(platformSetup, "dbVendor", dbVendor.getValue());

        //when - then
        assertThatExceptionOfType(PlatformException.class)
                .isThrownBy(platformSetup::init)
                .withMessage("Database vendor '%s' is not supported with the community edition", dbVendor);
    }

    @Test
    public void should_not_fail_if_init_with_postgres_database_vendor() {
        //given
        ReflectionTestUtils.setField(platformSetup, "dbVendor", DatabaseVendor.POSTGRES.getValue());

        //when - then
        assertThatNoException().isThrownBy(platformSetup::init);
    }

    @Test
    public void should_init_dbVendor_with_system_prop_if_null() throws Exception {
        //given
        ReflectionTestUtils.setField(platformSetup, "dbVendor", null);
        DatabaseVendor dbVendor = DatabaseVendor.POSTGRES;
        System.setProperty(BONITA_DB_VENDOR_PROPERTY, dbVendor.getValue());

        //when
        platformSetup.initProperties();

        //then
        assertThat(platformSetup.dbVendor).isEqualTo(dbVendor.getValue());
    }

    @Test
    public void should_store_tenant_configurationFile_when_initializing_platform() throws PlatformException {
        List<BonitaConfiguration> tenantTemplateEngineConfs = List
                .of(new BonitaConfiguration("tenantTemplateEngineConf", null));
        List<BonitaConfiguration> tenantTemplateSecurityScripts = List
                .of(new BonitaConfiguration("tenantTemplateSecurityScript", null));
        List<BonitaConfiguration> tenantTemplatePortalConfs = List
                .of(new BonitaConfiguration("tenantTemplatePortalConf", null));

        when(configurationService.getTenantTemplateEngineConf()).thenReturn(tenantTemplateEngineConfs);
        when(configurationService.getTenantTemplateSecurityScripts()).thenReturn(tenantTemplateSecurityScripts);
        when(configurationService.getTenantTemplatePortalConf()).thenReturn(tenantTemplatePortalConfs);

        platformSetup.init();

        verify(configurationService).storeTenantEngineConf(tenantTemplateEngineConfs, 1L);
        verify(configurationService).storeTenantSecurityScripts(tenantTemplateSecurityScripts, 1L);
        verify(configurationService).storeTenantPortalConf(tenantTemplatePortalConfs, 1L);
    }

    @Test
    public void should_not_store_tenant_configurationFile_when_platform_is_initialized() throws PlatformException {
        when(platformSetup.isPlatformAlreadyCreated()).thenReturn(true);

        platformSetup.init();

        verify(configurationService, never()).storeTenantEngineConf(any(), anyLong());
        verify(configurationService, never()).storeTenantSecurityScripts(any(), anyLong());
        verify(configurationService, never()).storeTenantPortalConf(any(), anyLong());
    }

    @Test
    public void getFolderFromConfiguration_should_work_for_platform_level_folder() throws Exception {
        // given:
        final Path setupFolder = temporaryFolder.newFolder().toPath();
        System.setProperty(BONITA_SETUP_FOLDER, setupFolder.toString());
        platformSetup.initProperties();

        LightBonitaConfiguration configuration = new LightBonitaConfiguration(0L, "some_folder");

        // when:
        final Path folder = platformSetup.getFolderFromConfiguration(configuration);

        // then:
        assertThat(folder).hasToString(separatorsToSystem(setupFolder + "/platform_conf/current/some_folder"));
    }

    @Test
    public void getFolderFromConfiguration_should_work_for_tenant_level_folder() throws Exception {
        // given:
        final Path setupFolder = temporaryFolder.newFolder().toPath();
        System.setProperty(BONITA_SETUP_FOLDER, setupFolder.toString());
        platformSetup.initProperties();

        LightBonitaConfiguration configuration = new LightBonitaConfiguration(2L, "TENANT-LEVEL-FOLDER");

        // when:
        final Path folder = platformSetup.getFolderFromConfiguration(configuration);

        // then:
        assertThat(folder)
                .hasToString(separatorsToSystem(setupFolder + "/platform_conf/current/tenants/2/tenant-level-folder"));
    }
}
