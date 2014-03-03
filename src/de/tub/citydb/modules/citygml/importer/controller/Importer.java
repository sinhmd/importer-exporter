/*
 * This file is part of the 3D City Database Importer/Exporter.
 * Copyright (c) 2007 - 2013
 * Institute for Geodesy and Geoinformation Science
 * Technische Universitaet Berlin, Germany
 * http://www.gis.tu-berlin.de/
 * 
 * The 3D City Database Importer/Exporter program is free software:
 * you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program. If not, see 
 * <http://www.gnu.org/licenses/>.
 * 
 * The development of the 3D City Database Importer/Exporter has 
 * been financially supported by the following cooperation partners:
 * 
 * Business Location Center, Berlin <http://www.businesslocationcenter.de/>
 * virtualcitySYSTEMS GmbH, Berlin <http://www.virtualcitysystems.de/>
 * Berlin Senate of Business, Technology and Women <http://www.berlin.de/sen/wtf/>
 */
package de.tub.citydb.modules.citygml.importer.controller;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.xml.bind.ValidationEvent;
import javax.xml.bind.ValidationEventHandler;
import javax.xml.namespace.QName;
import javax.xml.parsers.SAXParserFactory;

import org.citygml4j.builder.jaxb.JAXBBuilder;
import org.citygml4j.model.citygml.CityGML;
import org.citygml4j.model.citygml.CityGMLClass;
import org.citygml4j.model.citygml.core.CityModel;
import org.citygml4j.model.gml.GMLClass;
import org.citygml4j.xml.io.CityGMLInputFactory;
import org.citygml4j.xml.io.reader.CityGMLInputFilter;
import org.citygml4j.xml.io.reader.CityGMLReadException;
import org.citygml4j.xml.io.reader.CityGMLReader;
import org.citygml4j.xml.io.reader.FeatureReadMode;
import org.citygml4j.xml.io.reader.XMLChunk;

import de.tub.citydb.api.concurrent.PoolSizeAdaptationStrategy;
import de.tub.citydb.api.concurrent.WorkerPool;
import de.tub.citydb.api.event.Event;
import de.tub.citydb.api.event.EventDispatcher;
import de.tub.citydb.api.event.EventHandler;
import de.tub.citydb.api.log.LogLevel;
import de.tub.citydb.config.Config;
import de.tub.citydb.config.internal.Internal;
import de.tub.citydb.config.project.database.Database;
import de.tub.citydb.config.project.database.Workspace;
import de.tub.citydb.config.project.general.AffineTransformation;
import de.tub.citydb.config.project.importer.Index;
import de.tub.citydb.config.project.importer.XMLValidation;
import de.tub.citydb.database.DatabaseConnectionPool;
import de.tub.citydb.database.IndexStatusInfo;
import de.tub.citydb.database.IndexStatusInfo.IndexInfoObject;
import de.tub.citydb.database.IndexStatusInfo.IndexStatus;
import de.tub.citydb.database.IndexStatusInfo.IndexType;
import de.tub.citydb.io.DirectoryScanner;
import de.tub.citydb.io.DirectoryScanner.CityGMLFilenameFilter;
import de.tub.citydb.log.Logger;
import de.tub.citydb.modules.citygml.common.database.cache.CacheTableManager;
import de.tub.citydb.modules.citygml.common.database.uid.UIDCacheManager;
import de.tub.citydb.modules.citygml.common.database.uid.UIDCacheType;
import de.tub.citydb.modules.citygml.common.database.xlink.DBXlink;
import de.tub.citydb.modules.citygml.importer.concurrent.DBImportWorkerFactory;
import de.tub.citydb.modules.citygml.importer.concurrent.DBImportXlinkResolverWorkerFactory;
import de.tub.citydb.modules.citygml.importer.concurrent.DBImportXlinkWorkerFactory;
import de.tub.citydb.modules.citygml.importer.concurrent.FeatureReaderWorkerFactory;
import de.tub.citydb.modules.citygml.importer.database.uid.FeatureGmlIdCache;
import de.tub.citydb.modules.citygml.importer.database.uid.GeometryGmlIdCache;
import de.tub.citydb.modules.citygml.importer.database.uid.TextureImageCache;
import de.tub.citydb.modules.citygml.importer.database.xlink.resolver.DBXlinkSplitter;
import de.tub.citydb.modules.citygml.importer.util.AffineTransformer;
import de.tub.citydb.modules.common.event.CounterEvent;
import de.tub.citydb.modules.common.event.CounterType;
import de.tub.citydb.modules.common.event.EventType;
import de.tub.citydb.modules.common.event.FeatureCounterEvent;
import de.tub.citydb.modules.common.event.GeometryCounterEvent;
import de.tub.citydb.modules.common.event.InterruptEvent;
import de.tub.citydb.modules.common.event.StatusDialogMessage;
import de.tub.citydb.modules.common.event.StatusDialogProgressBar;
import de.tub.citydb.modules.common.event.StatusDialogTitle;
import de.tub.citydb.modules.common.filter.FilterMode;
import de.tub.citydb.modules.common.filter.ImportFilter;
import de.tub.citydb.modules.common.filter.statistic.FeatureCounterFilter;

public class Importer implements EventHandler {
	private final Logger LOG = Logger.getInstance();

	private final JAXBBuilder jaxbBuilder;
	private final DatabaseConnectionPool dbPool;
	private final Config config;
	private final EventDispatcher eventDispatcher;

	private WorkerPool<CityGML> dbWorkerPool;
	private WorkerPool<XMLChunk> featureWorkerPool;
	private WorkerPool<DBXlink> tmpXlinkPool;
	private WorkerPool<DBXlink> xlinkResolverPool;
	private CacheTableManager cacheTableManager;
	private DBXlinkSplitter tmpSplitter;
	private SAXParserFactory factory;

	private volatile boolean shouldRun = true;
	private AtomicBoolean isInterrupted = new AtomicBoolean(false);
	private EnumMap<CityGMLClass, Long> featureCounterMap;
	private EnumMap<GMLClass, Long> geometryCounterMap;
	private UIDCacheManager uidCacheManager;
	private DirectoryScanner directoryScanner;
	private long xmlValidationErrorCounter;

	private int runState;
	private final int PREPARING = 1;
	private final int PARSING = 2;
	private final int XLINK_RESOLVING = 3;

	public Importer(JAXBBuilder jaxbBuilder, 
			DatabaseConnectionPool dbPool, 
			Config config, 
			EventDispatcher eventDispatcher) {
		this.jaxbBuilder = jaxbBuilder;
		this.dbPool = dbPool;
		this.config = config;
		this.eventDispatcher = eventDispatcher;

		factory = SAXParserFactory.newInstance();
		factory.setNamespaceAware(true);

		featureCounterMap = new EnumMap<CityGMLClass, Long>(CityGMLClass.class);
		geometryCounterMap = new EnumMap<GMLClass, Long>(GMLClass.class);
	}

	public void cleanup() {
		eventDispatcher.removeEventHandler(this);
	}

	public boolean doProcess() {
		runState = PREPARING;

		// adding listeners
		eventDispatcher.addEventHandler(EventType.FEATURE_COUNTER, this);
		eventDispatcher.addEventHandler(EventType.GEOMETRY_COUNTER, this);
		eventDispatcher.addEventHandler(EventType.INTERRUPT, this);

		// get config shortcuts
		final de.tub.citydb.config.project.importer.Importer importer = config.getProject().getImporter();
		Database database = config.getProject().getDatabase();
		Internal intConfig = config.getInternal();		
		de.tub.citydb.config.project.system.System system = importer.getSystem();

		Index index = importer.getIndexes();

		// worker pool settings 
		int minThreads = system.getThreadPool().getDefaultPool().getMinThreads();
		int maxThreads = system.getThreadPool().getDefaultPool().getMaxThreads();
		int queueSize = maxThreads * 2;

		// gml:id lookup cache update
		int lookupCacheBatchSize = database.getUpdateBatching().getGmlIdCacheBatchValue();

		// checking workspace
		Workspace workspace = database.getWorkspaces().getImportWorkspace();
		if (shouldRun && dbPool.getActiveDatabaseAdapter().hasVersioningSupport() && 
				!dbPool.getActiveDatabaseAdapter().getWorkspaceManager().equalsDefaultWorkspaceName(workspace.getName()) &&
				!dbPool.getActiveDatabaseAdapter().getWorkspaceManager().existsWorkspace(workspace, true))
			return false;

		// deactivate database indexes
		if (shouldRun && (index.isSpatialIndexModeDeactivate() || index.isSpatialIndexModeDeactivateActivate() ||
				index.isNormalIndexModeDeactivate() || index.isNormalIndexModeDeactivateActivate())) {
			try {
				if (shouldRun && (index.isSpatialIndexModeDeactivate() || index.isSpatialIndexModeDeactivateActivate())) {
					LOG.info("Deactivating spatial indexes...");
					IndexStatusInfo indexStatus = dbPool.getActiveDatabaseAdapter().getUtil().dropSpatialIndexes();

					if (indexStatus != null) {				
						for (IndexInfoObject indexObj : indexStatus.getIndexObjects()) {							
							if (indexObj.getStatus() != IndexStatus.DROPPED) {
								LOG.error("FAILED: " + indexObj.toString());
								if (indexObj.hasErrorMessage())
									LOG.error("Error cause: " + indexObj.getErrorMessage());
							}
						}
					}
				} else
					dbPool.getActiveDatabaseAdapter().getUtil().getIndexStatus(IndexType.SPATIAL).printStatusToConsole();

				if (shouldRun && (index.isNormalIndexModeDeactivate() || index.isNormalIndexModeDeactivateActivate())) {
					LOG.info("Deactivating normal indexes...");
					IndexStatusInfo indexStatus = dbPool.getActiveDatabaseAdapter().getUtil().dropNormalIndexes();

					if (indexStatus != null) {				
						for (IndexInfoObject indexObj : indexStatus.getIndexObjects()) {							
							if (indexObj.getStatus() != IndexStatus.DROPPED) {
								LOG.error("FAILED: " + indexObj.toString());
								if (indexObj.hasErrorMessage())
									LOG.error("Error cause: " + indexObj.getErrorMessage());
							}
						}
					}
				} else
					dbPool.getActiveDatabaseAdapter().getUtil().getIndexStatus(IndexType.NORMAL).printStatusToConsole();

			} catch (SQLException e) {
				LOG.error("Database error while deactivating indexes: " + e.getMessage());
				return false;
			}			
		} else {
			try {
				for (IndexType type : IndexType.values())
					dbPool.getActiveDatabaseAdapter().getUtil().getIndexStatus(type).printStatusToConsole();
			} catch (SQLException e) {
				LOG.error("Database error while querying index status: " + e.getMessage());
				return false;
			}
		}

		// build list of import files
		LOG.info("Creating list of CityGML files to be imported...");	
		directoryScanner = new DirectoryScanner(true);
		directoryScanner.addFilenameFilter(new CityGMLFilenameFilter());		
		List<File> importFiles = directoryScanner.getFiles(intConfig.getImportFiles());

		if (!shouldRun)
			return true;

		if (importFiles.size() == 0) {
			LOG.warn("Failed to find CityGML files at the specified locations.");
			return false;
		}

		int fileCounter = 0;
		int remainingFiles = importFiles.size();
		LOG.info("List of import files successfully created.");
		LOG.info(remainingFiles + " file(s) will be imported.");

		// prepare CityGML input factory
		CityGMLInputFactory in = null;
		try {
			in = jaxbBuilder.createCityGMLInputFactory();
			in.setProperty(CityGMLInputFactory.FEATURE_READ_MODE, FeatureReadMode.SPLIT_PER_COLLECTION_MEMBER);
			in.setProperty(CityGMLInputFactory.FAIL_ON_MISSING_ADE_SCHEMA, false);
			in.setProperty(CityGMLInputFactory.PARSE_SCHEMA, false);
			in.setProperty(CityGMLInputFactory.SPLIT_AT_FEATURE_PROPERTY, new QName("generalizesTo"));
			in.setProperty(CityGMLInputFactory.EXCLUDE_FROM_SPLITTING, CityModel.class);
		} catch (CityGMLReadException e) {
			LOG.error("Failed to initialize CityGML parser. Aborting.");
			return false;
		}

		// prepare XML validation 
		XMLValidation xmlValidation = importer.getXMLValidation();
		if (xmlValidation.isSetUseXMLValidation()) {			
			LOG.info("Using XML validation during database import.");

			in.setProperty(CityGMLInputFactory.USE_VALIDATION, true);
			in.setProperty(CityGMLInputFactory.PARSE_SCHEMA, true);

			ValidationErrorHandler validationHandler = new ValidationErrorHandler();
			validationHandler.allErrors = !xmlValidation.isSetReportOneErrorPerFeature();
			in.setValidationEventHandler(validationHandler);
		}

		// affine transformation
		AffineTransformation affineTransformation = importer.getAffineTransformation();
		if (affineTransformation.isSetUseAffineTransformation()) {
			LOG.info("Applying affine coordinates transformation.");

			try {
				intConfig.setAffineTransformer(new AffineTransformer(config));
			} catch (Exception e) {
				LOG.error("The homogeneous transformation matrix is singular.");
				return false;
			}
		}

		// prepare counter filter
		FeatureCounterFilter counterFilter = new FeatureCounterFilter(config, FilterMode.IMPORT);
		Long counterFirstElement = counterFilter.getFilterState().get(0);
		Long counterLastElement = counterFilter.getFilterState().get(1);
		long elementCounter = 0;

		// prepare feature filter
		final ImportFilter importFilter = new ImportFilter(config);
		CityGMLInputFilter inputFilter = new CityGMLInputFilter() {
			public boolean accept(CityGMLClass type) {
				return type != CityGMLClass.APPEARANCE ? 
						!importFilter.getFeatureClassFilter().filter(type) : importer.getAppearances().isSetImportAppearance();
			}
		};

		runState = PARSING;

		while (shouldRun && fileCounter < importFiles.size()) {
			// check whether we reached the counter limit
			if (counterLastElement != null && elementCounter > counterLastElement)
				break;

			try {
				File file = importFiles.get(fileCounter++);
				intConfig.setImportPath(file.getParent());
				intConfig.setCurrentImportFile(file);

				eventDispatcher.triggerEvent(new StatusDialogTitle(file.getName(), this));
				eventDispatcher.triggerEvent(new StatusDialogMessage(Internal.I18N.getString("import.dialog.cityObj.msg"), this));
				eventDispatcher.triggerEvent(new StatusDialogProgressBar(true, this));
				eventDispatcher.triggerEvent(new CounterEvent(CounterType.FILE, --remainingFiles, this));

				// create instance of temp table manager
				try {
					cacheTableManager = new CacheTableManager(dbPool, maxThreads, config);
				} catch (SQLException e) {
					LOG.error("SQL error while initializing cache manager: " + e.getMessage());
					return false;
				} catch (IOException e) {
					LOG.error("I/O error while initializing cache manager: " + e.getMessage());
					return false;
				}

				// create instance of gml:id lookup server manager...
				uidCacheManager = new UIDCacheManager();

				// ...and start servers
				try {
					uidCacheManager.initCache(
							UIDCacheType.GEOMETRY,
							new GeometryGmlIdCache(cacheTableManager, 
									system.getGmlIdCache().getGeometry().getPartitions(), 
									lookupCacheBatchSize),
									system.getGmlIdCache().getGeometry().getCacheSize(),
									system.getGmlIdCache().getGeometry().getPageFactor(),
									maxThreads);

					uidCacheManager.initCache(
							UIDCacheType.FEATURE,
							new FeatureGmlIdCache(cacheTableManager, 
									system.getGmlIdCache().getFeature().getPartitions(),
									lookupCacheBatchSize),
									system.getGmlIdCache().getFeature().getCacheSize(),
									system.getGmlIdCache().getFeature().getPageFactor(),
									maxThreads);

					if (config.getProject().getImporter().getAppearances().isSetImportAppearance() &&
							config.getProject().getImporter().getAppearances().isSetImportTextureFiles()) {
						uidCacheManager.initCache(
								UIDCacheType.TEX_IMAGE,
								new TextureImageCache(cacheTableManager, 
										10,
										20),
										200000,
										90,
										maxThreads);
					}
				} catch (SQLException sqlEx) {
					LOG.error("SQL error while initializing database import: " + sqlEx.getMessage());
					continue;
				}

				// creating worker pools needed for data import
				// this pool is for registering xlinks
				tmpXlinkPool = new WorkerPool<DBXlink>(
						"xlink_importer_pool",
						minThreads,
						maxThreads,
						PoolSizeAdaptationStrategy.AGGRESSIVE,
						new DBImportXlinkWorkerFactory(dbPool, cacheTableManager, config, eventDispatcher),
						queueSize,
						false);

				// this pool basically works on the data import
				dbWorkerPool = new WorkerPool<CityGML>(
						"db_importer_pool",
						minThreads,
						maxThreads,
						PoolSizeAdaptationStrategy.AGGRESSIVE,
						new DBImportWorkerFactory(dbPool, 
								jaxbBuilder,
								tmpXlinkPool, 
								uidCacheManager, 
								importFilter,
								config, 
								eventDispatcher),
								queueSize,
								false);

				// this worker pool unmarshals the input file and passes xml chunks to the dbworker pool
				featureWorkerPool = new WorkerPool<XMLChunk>(
						"citygml_parser_pool",
						minThreads,
						maxThreads,
						PoolSizeAdaptationStrategy.AGGRESSIVE,
						new FeatureReaderWorkerFactory(dbWorkerPool, config, eventDispatcher),
						queueSize,
						false);

				// prestart threads
				tmpXlinkPool.prestartCoreWorkers();
				dbWorkerPool.prestartCoreWorkers();
				featureWorkerPool.prestartCoreWorkers();

				// fail if we could not start a single import worker
				if (dbWorkerPool.getPoolSize() == 0) {
					LOG.error("Failed to start database import worker pool. Check the database connection pool settings.");
					return false;
				}

				// ok, preparation done. inform user and start parsing the input file
				CityGMLReader reader = null;
				try {
					reader = in.createFilteredCityGMLReader(in.createCityGMLReader(file), inputFilter);	
					LOG.info("Importing file: " + file.toString());						

					while (shouldRun && reader.hasNext()) {
						XMLChunk chunk = reader.nextChunk();

						if (counterFilter.isActive()) {
							elementCounter++;

							if (counterFirstElement != null && elementCounter < counterFirstElement)
								continue;

							if (counterLastElement != null && elementCounter > counterLastElement)				
								break;
						}

						featureWorkerPool.addWork(chunk);
					}					
				} catch (CityGMLReadException e) {
					LOG.error("Fatal CityGML parser error: " + e.getCause().getMessage());
					continue;
				}

				// we are done with parsing. so shutdown the workers
				// xlink pool is not shutdown because we need it afterwards
				try {
					featureWorkerPool.shutdownAndWait();
				} catch (InterruptedException ie) {
					//
				}

				try {
					reader.close();
				} catch (CityGMLReadException e) {
					//
				}

				try {
					dbWorkerPool.shutdownAndWait();
					tmpXlinkPool.join();
				} catch (InterruptedException ie) {
					//
				}

				if (shouldRun) {
					runState = XLINK_RESOLVING;

					// get an xlink resolver pool
					LOG.info("Resolving XLink references.");
					xlinkResolverPool = new WorkerPool<DBXlink>(
							"xlink_resolver_pool",
							minThreads,
							maxThreads,
							PoolSizeAdaptationStrategy.AGGRESSIVE,
							new DBImportXlinkResolverWorkerFactory(dbPool, 
									tmpXlinkPool, 
									uidCacheManager, 
									cacheTableManager, 
									importFilter,
									config, 
									eventDispatcher),
									queueSize,
									false);

					// prestart its workers
					xlinkResolverPool.prestartCoreWorkers();

					// we also need a splitter which extracts the data from the temp tables
					tmpSplitter = new DBXlinkSplitter(cacheTableManager, 
							xlinkResolverPool, 
							tmpXlinkPool,
							eventDispatcher);

					// resolve xlinks
					try {
						if (shouldRun)
							tmpSplitter.startQuery();
					} catch (SQLException sqlE) {
						LOG.error("SQL error: " + sqlE.getMessage());
					}

					// shutdown worker pools
					try {
						xlinkResolverPool.shutdownAndWait();
						tmpXlinkPool.shutdownAndWait();
					} catch (InterruptedException iE) {
						//
					}
				} else {
					// at least shutdown tmp xlink pool
					try {
						tmpXlinkPool.shutdownAndWait();
					} catch (InterruptedException iE) {
						//
					}
				}

				eventDispatcher.triggerEvent(new StatusDialogMessage(Internal.I18N.getString("import.dialog.finish.msg"), this));
				eventDispatcher.triggerEvent(new StatusDialogProgressBar(true, this));

				// finally clean up and join eventDispatcher
				try {
					uidCacheManager.shutdownAll();
				} catch (SQLException e) {
					LOG.error("SQL error: " + e.getMessage());
				}

				try {
					LOG.info("Cleaning temporary cache.");
					cacheTableManager.dropAll();
					cacheTableManager = null;
				} catch (SQLException sqlE) {
					LOG.error("SQL error: " + sqlE.getMessage());
				}

				try {
					eventDispatcher.flushEvents();
				} catch (InterruptedException e) {
					// 
				}

				// show XML validation errors
				if (xmlValidation.isSetUseXMLValidation() && xmlValidationErrorCounter > 0)
					LOG.warn(xmlValidationErrorCounter + " error(s) encountered while validating the document.");

				xmlValidationErrorCounter = 0;
			} finally {
				// clean up
				if (featureWorkerPool != null && !featureWorkerPool.isTerminated())
					featureWorkerPool.shutdownNow();

				if (dbWorkerPool != null && !dbWorkerPool.isTerminated())
					dbWorkerPool.shutdownNow();

				if (tmpXlinkPool != null && !tmpXlinkPool.isTerminated())
					tmpXlinkPool.shutdownNow();

				if (xlinkResolverPool != null && !xlinkResolverPool.isTerminated())
					xlinkResolverPool.shutdownNow();

				if (cacheTableManager != null) {
					try {
						LOG.info("Cleaning temporary cache.");
						cacheTableManager.dropAll();
						cacheTableManager = null;
					} catch (SQLException sqlEx) {
						LOG.error("SQL error while finishing database import: " + sqlEx.getMessage());
					}
				}

				// set to null
				uidCacheManager = null;
				tmpXlinkPool = null;
				dbWorkerPool = null;
				featureWorkerPool = null;
				xlinkResolverPool = null;
				tmpSplitter = null;				
			}
		} 	

		// reactivate database indexes
		if (shouldRun) {
			if (index.isSpatialIndexModeDeactivateActivate() || index.isNormalIndexModeDeactivateActivate()) {
				try {
					if (index.isSpatialIndexModeDeactivateActivate()) {
						LOG.info("Activating spatial indexes. This can take long time...");
						IndexStatusInfo indexStatus = dbPool.getActiveDatabaseAdapter().getUtil().createSpatialIndexes();

						if (indexStatus != null) {				
							for (IndexInfoObject indexObj : indexStatus.getIndexObjects()) {							
								if (indexObj.getStatus() != IndexStatus.VALID) {
									LOG.error("FAILED: " + indexObj.toString());
									if (indexObj.hasErrorMessage())
										LOG.error("Error cause: " + indexObj.getErrorMessage());
								}
							}
						}
					}

					if (index.isNormalIndexModeDeactivateActivate()) {
						LOG.info("Activating normal indexes. This can take long time...");
						IndexStatusInfo indexStatus = dbPool.getActiveDatabaseAdapter().getUtil().createNormalIndexes();

						if (indexStatus != null) {				
							for (IndexInfoObject indexObj : indexStatus.getIndexObjects()) {							
								if (indexObj.getStatus() != IndexStatus.VALID) {
									LOG.error("FAILED: " + indexObj.toString());
									if (indexObj.hasErrorMessage())
										LOG.error("Error cause: " + indexObj.getErrorMessage());
								}
							}
						}
					}

				} catch (SQLException e) {
					LOG.error("Database error while activating indexes: " + e.getMessage());
					return false;
				}
			}
		}

		// show imported features
		if (!featureCounterMap.isEmpty()) {
			LOG.info("Imported CityGML features:");
			for (CityGMLClass type : featureCounterMap.keySet())
				LOG.info(type + ": " + featureCounterMap.get(type));
		}

		long geometryObjects = 0;
		for (GMLClass type : geometryCounterMap.keySet())
			geometryObjects += geometryCounterMap.get(type);

		if (geometryObjects != 0)
			LOG.info("Processed geometry objects: " + geometryObjects);

		return shouldRun;
	}

	// react on events we are receiving via the eventDispatcher
	@Override
	public void handleEvent(Event e) throws Exception {
		if (e.getEventType() == EventType.FEATURE_COUNTER) {
			HashMap<CityGMLClass, Long> counterMap = ((FeatureCounterEvent)e).getCounter();

			for (CityGMLClass type : counterMap.keySet()) {
				Long counter = featureCounterMap.get(type);
				Long update = counterMap.get(type);

				if (counter == null)
					featureCounterMap.put(type, update);
				else
					featureCounterMap.put(type, counter + update);
			}
		}

		else if (e.getEventType() == EventType.GEOMETRY_COUNTER) {
			HashMap<GMLClass, Long> counterMap = ((GeometryCounterEvent)e).getCounter();

			for (GMLClass type : counterMap.keySet()) {
				Long counter = geometryCounterMap.get(type);
				Long update = counterMap.get(type);

				if (counter == null)
					geometryCounterMap.put(type, update);
				else
					geometryCounterMap.put(type, counter + update);
			}
		}

		else if (e.getEventType() == EventType.INTERRUPT) {
			if (isInterrupted.compareAndSet(false, true)) {
				switch (((InterruptEvent)e).getInterruptType()) {
				case ADE_SCHEMA_READ_ERROR:
				case USER_ABORT:
					shouldRun = false;
					break;
				}

				String log = ((InterruptEvent)e).getLogMessage();
				if (log != null)
					LOG.log(((InterruptEvent)e).getLogLevelType(), log);

				if (runState == PREPARING && directoryScanner != null)
					directoryScanner.stopScanning();

				if (runState == XLINK_RESOLVING && tmpSplitter != null)
					tmpSplitter.shutdown();
			}
		}
	}

	private final class ValidationErrorHandler implements ValidationEventHandler {
		boolean allErrors = false;

		@Override
		public boolean handleEvent(ValidationEvent event) {
			StringBuilder msg = new StringBuilder();
			LogLevel type;

			switch (event.getSeverity()) {
			case ValidationEvent.FATAL_ERROR:
			case ValidationEvent.ERROR:
				msg.append("Invalid content");
				type = LogLevel.ERROR;
				break;
			case ValidationEvent.WARNING:
				msg.append("Warning");
				type = LogLevel.WARN;
				break;
			default:
				return allErrors;
			}

			msg.append(": ").append(event.getMessage());
			LOG.log(type, msg.toString());

			xmlValidationErrorCounter++;
			return allErrors;
		}

	}

}
