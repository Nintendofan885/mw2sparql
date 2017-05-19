/*
 * Copyright (c) 2017 MW2SPARQL developers.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mediawiki.sparql.mwontop.sql;

import it.unibz.inf.ontop.owlrefplatform.core.QuestConstants;
import it.unibz.inf.ontop.owlrefplatform.core.QuestPreferences;
import it.unibz.inf.ontop.sesame.SesameVirtualRepo;
import it.unibz.inf.ontop.sql.ImplicitDBConstraintsReader;
import org.apache.commons.io.IOUtils;
import org.mediawiki.sparql.mwontop.Configuration;
import org.mediawiki.sparql.mwontop.utils.InternalFilesManager;
import org.openrdf.model.Model;
import org.openrdf.repository.Repository;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * @author Thomas Pellissier Tanon
 */
public class RepositoryFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryFactory.class);
    private static final RepositoryFactory INSTANCE = new RepositoryFactory();

    public static RepositoryFactory getInstance() {
        return INSTANCE;
    }

    private OWLOntology owlOntology;
    private ImplicitDBConstraintsReader dbConstraints;
    private Map<String,SiteConfig> sitesConfig;

    private RepositoryFactory() {
        try {
            owlOntology = loadOWLOntology();
            System.out.println(RepositoryFactory.class.getResource("/db_constraints.txt").getPath());
            dbConstraints = loadDBConstraints();
            sitesConfig = loadSitesConfig();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
            System.exit(1);
        }
    }

    private Map<String,Repository> repositories = new HashMap<>();

    public Repository getRepositoryForSiteId(String siteId) throws Exception {
        if(!repositories.containsKey(siteId)) {
            repositories.put(siteId, buildRepositoryForSiteId(siteId));
        }
        return repositories.get(siteId);
    }

    private Repository buildRepositoryForSiteId(String siteId) throws Exception {
        return buildVirtualRepository(connectionInformationForSiteId(siteId), sitesConfig.get(siteId));
    }

    private MySQLConnectionInformation connectionInformationForSiteId(String siteId) {
        Configuration configuration = Configuration.getInstance();
        return new MySQLConnectionInformation(
                configuration.getDatabaseHostPattern().replace("{siteId}", siteId),
                siteId + "_p",
                configuration.getDatabaseUser(),
                configuration.getDatabasePassword()
        );
    }

    private Repository buildVirtualRepository(MySQLConnectionInformation connectionInformation, SiteConfig siteConfig) throws Exception {
        QuestPreferences preferences = new QuestPreferences();
        preferences.setCurrentValueOf(QuestPreferences.ABOX_MODE, QuestConstants.VIRTUAL);
        preferences.setCurrentValueOf(QuestPreferences.DBNAME, connectionInformation.getDatabaseName());
        preferences.setCurrentValueOf(QuestPreferences.JDBC_DRIVER, "com.mysql.jdbc.Driver");
        preferences.setCurrentValueOf(
                QuestPreferences.JDBC_URL,
                "jdbc:mysql://" + connectionInformation.getHost() + "/" + connectionInformation.getDatabaseName()
                        + "?sessionVariables=sql_mode='ANSI'"
        );
        preferences.setCurrentValueOf(QuestPreferences.DBUSER, connectionInformation.getUser());
        preferences.setCurrentValueOf(QuestPreferences.DBPASSWORD, connectionInformation.getPassword());

        SesameVirtualRepo repository = new SesameVirtualRepo(
                connectionInformation.getDatabaseName(),
                owlOntology,
                loadRDFMappingModel(siteConfig),
                preferences
        );
        repository.setImplicitDBConstraints(dbConstraints);
        repository.initialize();
        return repository;
    }

    private OWLOntology loadOWLOntology() throws Exception {
        try(InputStream inputStream = this.getClass().getResourceAsStream("/ontology.ttl")) {
            return OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(inputStream);
            //TODO: if fails, convert to OWL using Rio
        }
    }

    private Model loadRDFMappingModel(SiteConfig siteConfig) throws Exception {
        return InternalFilesManager.parseTurtle(
                InternalFilesManager.getFileAsString("/mapping.ttl")
                        .replace("{lang}", siteConfig.getLanguageCode())
                        .replace("{base_url}", siteConfig.getBaseURL().replace("https://", "http://")) //TODO: crazy restriction in ontop 1.18.0.1
        );
    }

    private ImplicitDBConstraintsReader loadDBConstraints() throws IOException {
        //ImplicitDBConstraintsReader takes a file as input so we have to do a copy
        File file = File.createTempFile("db_constraints", ".tmp");
        file.deleteOnExit();
        try(
                InputStream inputStream = getClass().getResourceAsStream("/db_constraints.txt");
                OutputStream outputStream = new FileOutputStream(file)
        ) {
            IOUtils.copy(inputStream, outputStream);
        }
        return new ImplicitDBConstraintsReader(file);
    }

    private Map<String,SiteConfig> loadSitesConfig() throws Exception {
        MySQLConnectionInformation connectionInformation = connectionInformationForSiteId("enwiki");
        try(Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + connectionInformation.getHost() + "/" + connectionInformation.getDatabaseName(),
                connectionInformation.getUser(), connectionInformation.getPassword()
        )) {
            try(ResultSet resultSet = connection.createStatement().executeQuery("SELECT dbname, lang, name, url FROM meta_p.wiki;")) {
                Map<String,SiteConfig> siteConfig = new HashMap<>();
                while (resultSet.next()) {
                    siteConfig.put(resultSet.getString("dbname"), new SiteConfig(
                            resultSet.getString("dbname"),
                            resultSet.getString("lang"),
                            resultSet.getString("name"),
                            resultSet.getString("url")
                    ));
                }
                return siteConfig;
            }
        }
    }

    private static class MySQLConnectionInformation {
        private String host;
        private String dbName;
        private String user;
        private String password;

        MySQLConnectionInformation(String host, String dbName, String user, String password) {
            this.host = host;
            this.dbName = dbName;
            this.user = user;
            this.password = password;
        }

        String getHost() {
            return host;
        }

        String getDatabaseName() {
            return dbName;
        }

        String getUser() {
            return user;
        }

        String getPassword() {
            return password;
        }
    }

    private static class SiteConfig {
        private String dbName;
        private String lang;
        private String name;
        private String url;

        SiteConfig(String dbName, String lang, String name, String url) {
            this.dbName = dbName;
            this.lang = lang;
            this.name = name;
            this.url = url;
        }

        String getDatabaseName() {
            return dbName;
        }

        String getLanguageCode() {
            return lang;
        }

        String getSiteName() {
            return name;
        }

        String getBaseURL() {
            return url;
        }
    }
}
