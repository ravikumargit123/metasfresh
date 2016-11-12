package de.metas.dlm.partitioner.impl;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.adempiere.ad.dao.IQueryBL;
import org.adempiere.ad.dao.IQueryBuilder;
import org.adempiere.ad.dao.impl.UpperCaseQueryFilterModifier;
import org.adempiere.ad.table.api.IADTableDAO;
import org.adempiere.ad.trx.api.ITrx;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.ad.trx.api.ITrxRunConfig;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.model.IContextAware;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.model.PlainContextAware;
import org.adempiere.util.Check;
import org.adempiere.util.ILoggable;
import org.adempiere.util.Services;
import org.adempiere.util.lang.ITableRecordReference;
import org.adempiere.util.lang.Mutable;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.Adempiere;
import org.compiere.model.IQuery;
import org.compiere.model.I_AD_Column;
import org.compiere.util.Env;
import org.compiere.util.TrxRunnable;
import org.compiere.util.TrxRunnableAdapter;
import org.slf4j.Logger;

import com.google.common.annotations.VisibleForTesting;

import de.metas.adempiere.service.IColumnBL;
import de.metas.connection.IConnectionCustomizerService;
import de.metas.connection.ITemporaryConnectionCustomizer;
import de.metas.dlm.IDLMService;
import de.metas.dlm.Partition;
import de.metas.dlm.Partition.WorkQueue;
import de.metas.dlm.connection.DLMConnectionCustomizer;
import de.metas.dlm.exception.DLMReferenceException;
import de.metas.dlm.exception.TableNotAddedToDLMException;
import de.metas.dlm.migrator.IMigratorService;
import de.metas.dlm.model.IDLMAware;
import de.metas.dlm.model.I_AD_Table;
import de.metas.dlm.model.I_DLM_Partition;
import de.metas.dlm.model.I_DLM_Partition_Config;
import de.metas.dlm.model.I_DLM_Partition_Config_Line;
import de.metas.dlm.model.I_DLM_Partition_Config_Reference;
import de.metas.dlm.model.I_DLM_Partition_Record;
import de.metas.dlm.model.I_DLM_Partition_Workqueue;
import de.metas.dlm.partitioner.IPartitionerService;
import de.metas.dlm.partitioner.PartitionRequestFactory;
import de.metas.dlm.partitioner.PartitionRequestFactory.CreatePartitionRequest;
import de.metas.dlm.partitioner.PartitionRequestFactory.CreatePartitionRequest.OnNotDLMTable;
import de.metas.dlm.partitioner.config.PartitionerConfig;
import de.metas.dlm.partitioner.config.PartitionerConfig.Builder;
import de.metas.dlm.partitioner.config.PartitionerConfigLine;
import de.metas.dlm.partitioner.config.PartitionerConfigLine.LineBuilder;
import de.metas.dlm.partitioner.config.PartitionerConfigReference;
import de.metas.dlm.partitioner.config.PartitionerConfigReference.RefBuilder;
import de.metas.dlm.partitioner.config.TableReferenceDescriptor;
import de.metas.logging.LogManager;

/*
 * #%L
 * metasfresh-dlm
 * %%
 * Copyright (C) 2016 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

public class PartitionerService implements IPartitionerService
{

	private final transient Logger logger = LogManager.getLogger(getClass());

	@Override
	public List<Partition> createPartition(final CreatePartitionRequest request)
	{
		final ITrxManager trxManager = Services.get(ITrxManager.class);

		final ITrxRunConfig trxRunnableConfig = trxManager.newTrxRunConfigBuilder()
				.setAutoCommit(true) // there will be a lot of selects, and we don't want them to accumulate locks.
				.build();

		final Mutable<List<Partition>> result = new Mutable<>();

		final IConnectionCustomizerService connectionCustomizerService = Services.get(IConnectionCustomizerService.class);

		try (final AutoCloseable temporaryCustomizer = connectionCustomizerService.registerTemporaryCustomizer(createConnectionCustomizer()))
		{
			trxManager.run(ITrx.TRXNAME_None,
					trxRunnableConfig,
					new TrxRunnableAdapter()
					{
						@Override
						public void run(final String localTrxName) throws Exception
						{
							final List<Partition> createdPartitions = createPartition0(request);
							result.setValue(createdPartitions);
						}
					});
		}
		catch (final Exception e)
		{
			throw AdempiereException.wrapIfNeeded(e);
		}
		return result.getValue();
	}

	@VisibleForTesting
	/* package */ List<Partition> createPartition0(final CreatePartitionRequest request)
	{
		final PartitionerConfig config = request.getConfig();

		// we need to get to the "first" line(s),
		// i.e. those DLM_PartitionLine_Configs that are not referenced via any DLM_PartitionReference_Config.DLM_Referencing_PartitionLine_Config_ID
		// i think we can also live with circles, i.e. if there is no "first", but for those "firsts" we should pick one and start with it
		// also, for the case of >1 "firsts", we need to be able to backtrack
		final List<PartitionerConfigLine> lines = config.getLines(); // DLM_PartitionLine_Config

		if (lines.isEmpty())
		{
			return Collections.singletonList(new Partition().withConfig(config)); // return one empty partition
		}

		// make sure the tables of which we might add records are all ready for DLM
		checkIfAllTablesAreDLM(lines, request.getOnNotDLMTable());

		final List<Partition> partitions = new ArrayList<>();
		final PlainContextAware ctxAware = PlainContextAware.newWithThreadInheritedTrx(Env.getCtx());

		if (request.getRecordToAttach() != null)
		{
			final ITableRecordReference tableRef = request.getRecordToAttach();
			final IDLMAware record = tableRef.getModel(ctxAware, IDLMAware.class);
			if (record != null)
			{
				final Partition partition = attachToPartitionAndCheck(
						request,
						new IterateResult(
								Collections.singletonList(WorkQueue.of(ITableRecordReference.FromModelConverter.convert(record))).iterator(),
								ctxAware));
				partitions.add(partition);
			}
		}

		if (request.getPartitionToComplete() != null)
		{
			final I_DLM_Partition partitionDB = request.getPartitionToComplete();

			final Iterator<WorkQueue> queue = PartitionerTools.loadQueue(partitionDB.getDLM_Partition_ID(), ctxAware);

			final Partition partition = attachToPartitionAndCheck(
					request,
					new IterateResult(
							queue,
							ctxAware));
			partitions.add(partition);
		}

		if (request.getRecordToAttach() == null && request.getPartitionToComplete() == null)
		{
			// TODO addLog
			final I_DLM_Partition incompletePartitionDB = Services.get(IQueryBL.class).createQueryBuilder(I_DLM_Partition.class, ctxAware)
					.addOnlyActiveRecordsFilter()
					.addEqualsFilter(I_DLM_Partition.COLUMN_IsPartitionComplete, false)
					.orderBy().addColumn(I_DLM_Partition.COLUMN_DLM_Partition_ID).endOrderBy()
					.create().first();

			if (incompletePartitionDB != null)
			{
				final Iterator<WorkQueue> queue = PartitionerTools.loadQueue(incompletePartitionDB.getDLM_Partition_ID(), ctxAware);

				final Partition partition = attachToPartitionAndCheck(
						request,
						new IterateResult(
								queue,
								ctxAware));
				partitions.add(partition);
			}
			else
			{
				// iterate the lines and look for the first record out o
				for (final PartitionerConfigLine line : lines)
				{
					final IDLMAware record = retrieveUnpartitionedRecord(line.getTableName(), request.isOldestFirst());
					if (record == null)
					{
						continue;  // looks like we partitioned *every* record of the given table
					}
					final Partition partition = attachToPartitionAndCheck(request,
							new IterateResult(
									Collections.singletonList(WorkQueue.of(ITableRecordReference.FromModelConverter.convert(record))).iterator(),
									ctxAware));
					partitions.add(partition);
				}
			}
		}
		return partitions;
	}

	private Partition attachToPartitionAndCheck(
			final CreatePartitionRequest request,
			final IterateResult initialResult)
	{
		final PartitionerConfig config = request.getConfig();

		final IterateResult result = attachToPartition(initialResult, config);
		final Partition partition = result.getPartition();

		try
		{
			// now figure out if records are missing:
			// update each records' DLM_Level to 1 (1="test").
			final IMigratorService migratorService = Services.get(IMigratorService.class);

			final String msg = "Calling testMigratePartition with partition={}";
			logger.info(msg, partition);
			migratorService.testMigratePartition(partition);
		}
		catch (final DLMReferenceException e)
		{
			final TableReferenceDescriptor descriptor = e.getTableReferenceDescriptor();

			// if there is a DLMException, then depending on our config (LATER),
			// throw an exception (LATER),
			// skip the record (LATER)
			// or add another PartitionerConfigLine, get the additional line's records and retry.
			final String msg = "Caught {}; going to retry with an augmented config that also includes referencingTable={}";
			final Object[] msgParameters = { e.toString(), descriptor.getReferencingTableName() };
			logger.info(msg, msgParameters);
			ILoggable.THREADLOCAL.getLoggable().addLog(msg, msgParameters);

			final PartitionerConfig newConfig = augmentPartitionerConfig(config, Collections.singletonList(descriptor));
			storeOutOfTrx(newConfig); // store the new config so that even if we fail later on, the info is preserved

			// when adding another PartitionerConfigLine but the table is not DLM'ed yet, then DLM it on the fly.
			checkIfTableIsDLM(descriptor.getReferencingTableName(), request.getOnNotDLMTable());

			// call this method again, i.e. start over with our augmented config
			final CreatePartitionRequest newRequest = PartitionRequestFactory
					.builder(request)
					.setConfig(newConfig)
					.build();

			final String referencedTableName = descriptor.getReferencedTableName();

			// Check.errorUnless(partition.getRecordsWithTable(referencedTableName).isEmpty(),
			// "partition.getRecordsWithTable({}) should return an empty list because we stored & flushed this before we did the testmigration invokation that lead us into this catch-block; partition={}",
			// referencedTableName, partition);

			// retrieve all the record that might also be referenced from outside the partition via the new partitioner config augment.
			final Iterator<WorkQueue> recordReferencesForTable = loadForTable(partition, referencedTableName);
			final IterateResult iterateResult = new IterateResult(recordReferencesForTable, PlainContextAware.newWithThreadInheritedTrx());
			iterateResult.clearAfterPartitionStored(partition);
			return attachToPartitionAndCheck(newRequest, iterateResult);
		}

		final String msg = "Returning a newly identified partition={}.";
		logger.info(msg, partition);
		ILoggable.THREADLOCAL.getLoggable().addLog(msg, partition);

		return partition;
	}

	// private Partition loadRecordsIfNeccesary(Partition partition)
	// {
	// if (partition.getDLM_Partition_ID() <= 0)
	// {
	// return partition;
	// }
	//
	// final Map<String, Collection<ITableRecordReference>> allRecords = loadAllRecords(partition.getDLM_Partition_ID(), PlainContextAware.newWithThreadInheritedTrx(Env.getCtx()));
	// final Partition partitionReloaded = partition.withRecords(allRecords);
	//
	// logger.info("(Re)loaded the partition={}", partitionReloaded);
	//
	// return partitionReloaded;
	// }

	private void checkIfAllTablesAreDLM(final List<PartitionerConfigLine> lines, final OnNotDLMTable onNotDLMTable)
	{
		final Set<String> checkedTableNames = new HashSet<>();
		for (final PartitionerConfigLine line : lines)
		{
			if (checkedTableNames.add(line.getTableName()))
			{
				checkIfTableIsDLM(line.getTableName(), onNotDLMTable);
			}

			for (final PartitionerConfigReference ref : line.getReferences())
			{
				if (checkedTableNames.add(ref.getReferencedTableName()))
				{
					checkIfTableIsDLM(ref.getReferencedTableName(), onNotDLMTable);
				}
			}
		}
	}

	private IDLMAware retrieveUnpartitionedRecord(
			final String tableName,
			final boolean oldestFirst)
	{
		Check.assumeNotNull(tableName, "Parameter 'tableName' is not null");

		final IColumnBL columnBL = Services.get(IColumnBL.class);

		// we will order by key column names, so that generally, older records are partitioned first. But note that a particular order is not a "must"
		// so, we don't need to have a single key column here, but later, we do need it, so let's fail early in case the given table does not yet have a single key column.
		final String keyColumnName = columnBL.getSingleKeyColumn(tableName);

		// AD_Table_ID
		// get the "record" from DLM_PartitionLine_Config.AD_Table_ID as the database record with the smallest ID
		// that does not yet have a DLM_Partition_ID
		final IDLMAware record = Services.get(IQueryBL.class)
				.createQueryBuilder(IDLMAware.class, tableName, PlainContextAware.newWithThreadInheritedTrx(Env.getCtx()))
				// .addOnlyActiveRecordsFilter() we want to partition both active and inactive records
				.addEqualsFilter(IDLMAware.COLUMNNAME_DLM_Partition_ID, null)
				.orderBy()
				.addColumn(keyColumnName, oldestFirst)
				.endOrderBy()
				.create()
				.first();
		logger.debug("Retrieved unpartitioned IDLMAware={} from table={}", record, tableName);
		return record;
	}

	/**
	 * The method loads both the records which are referenced by the given <code>firstRecord</code> ("forward") and the records which do reference the given <code>firstRecord</code> ("backward") and adds them result.
	 * For those forward and backward references that are not yet part of any partion (i.e. {@link IDLMAware#COLUMNNAME_DLM_Partition_ID} <code>== 0</code>), it repeats the procedure.
	 * The method finishes and returns the result when there are no further records to repeat that procedure with.
	 *
	 * @param config
	 * @param ctxAware
	 * @param firstRecord the record to start with. It is assumed that this does not belong to any partition.
	 *
	 * @return the set of all records that referece or are referenced by the given <code>firstRecord</code> (directly or indirectly!) and
	 *         <li>either have <code>DLM_Partition_ID==0</code>
	 *         <li>or belong to the "outer border" of an existing partition. That means that they have DLM_Partition_ID>0, but are directly or indirectly referenced from the given <code>firstRecord</code>.
	 */
	private IterateResult iterate(
			final PartitionerConfig config,
			final IContextAware ctxAware,
			final IterateResult result)
	{

		final IColumnBL columnBL = Services.get(IColumnBL.class);
		final IADTableDAO adTableDAO = Services.get(IADTableDAO.class);

		// store what we are setting out to do here.
		// E.g. if we are called from a DLMException, we want the situation - partition is not complete because testMigrate failed, and there are e.g. 20 orderlines to backtrack from - to be sotere here.
		// otherwise, the partiton we are in truth working on just now would be flagged as "completed" in the DB until further notice
		storeIterateResult(config, result, ctxAware);

		while (!result.isQueueEmpty())
		{
			final ITableRecordReference currentReference = result.nextFromQueue();
			final IDLMAware currentRecord = currentReference.getModel(ctxAware, IDLMAware.class);
			if (currentRecord == null)
			{
				continue;
			}

			if (shallStoreResult(result))
			{
				storeIterateResult(config, result, ctxAware);
			}

			final String currentTableName = currentReference.getTableName();
			final int currentRecordId = InterfaceWrapperHelper.getId(currentRecord);

			// there might or migth not be a line for the current reference's table name. That would mean that we can only search "backward"
			final Optional<PartitionerConfigLine> currentLineOrNull = config.getLine(currentTableName);

			if (currentLineOrNull.isPresent())
			{
				final List<PartitionerConfigReference> forwardRefs = currentLineOrNull.get().getReferences();

				// look FORWARD
				//
				// look at all the records that are referenced by 'currentRecord' and add them to 'hull',
				// but only add them if they were not yet identified as parts of this partition (i.e. not yet added to 'records').
				for (final PartitionerConfigReference forwardRef : forwardRefs)
				{
					// the table name for the foreign record which has 'foreignKey' as its ID
					final String forwardTableName = forwardRef.getReferencedTableName().toLowerCase(); // toLowerCase to avoid trouble if a config was just augmented from a DLMException.
					final String forwardColumnName = forwardRef.getReferencingColumnName();

					// first check if this is all about a Record_ID/AD_Table_ID reference.
					// if that is the case, then we need to verify that the AD_Table_ID of 'record' actually points to the table named 'forwardTableName'
					if (columnBL.isRecordColumnName(forwardColumnName))
					{
						final String tableColumnName = columnBL.getTableColumnName(currentTableName, forwardColumnName)
								.orElseThrow(Check.supplyEx("Table={} has no table column name for recordColumnName={}", currentTableName, forwardColumnName));

						final Integer tableId = InterfaceWrapperHelper.getValueOrNull(currentRecord, tableColumnName);
						if (tableId == null || tableId <= 0)
						{
							logger.trace("{}[{}] forward: the column={} does not reference any table; skipping", currentTableName, currentRecordId, forwardColumnName, forwardTableName, tableColumnName);
							continue;
						}

						final String tableName = adTableDAO.retrieveTableName(tableId);
						if (!tableName.equalsIgnoreCase(forwardTableName))
						{
							logger.trace("{}[{}] forward: the column={} does not reference a {}-record, but a {}-record; skipping", currentTableName, currentRecordId, forwardColumnName, forwardTableName, tableName);
							continue;
						}
					}

					// get the foreign key ID of
					// table DLM_PartitionLine_Config.AD_Table_ID,
					// column DLM_PartitionReference_Config.DLM_Referencing_Column_ID
					final Integer forwardKey = InterfaceWrapperHelper.getValueOrNull(currentRecord, forwardColumnName);
					if (forwardKey == null || forwardKey <= 0)
					{
						logger.trace("{}[{}] forward: the column={} does not reference anything; skipping", currentTableName, currentRecordId, forwardColumnName);
						continue;
					}

					final TableRecordReference forwardReference = new TableRecordReference(forwardTableName, forwardKey);

					final boolean recordWasAlreadyAddedBefore = result.contains(forwardReference);
					if (recordWasAlreadyAddedBefore)
					{
						logger.trace("{}[{}] forward: ITableRecordReference={} was already added in a previous iteration. Returning", currentTableName, currentRecordId, forwardReference); // avoid circles and also avoid loading the whole PO again
					}
					else
					{
						// the foreign record was not yet added before. Load it now.
						final IDLMAware forwardRecord = forwardReference.getModel(ctxAware, IDLMAware.class);
						if (forwardRecord == null)
						{
							// this happens with our "minidump" where we left out the HUs
							logger.debug("{}[{}] forward: the record from table={} which we attempted to load via {}.{}={} is NULL",
									currentTableName, currentRecordId, forwardTableName, currentTableName, forwardColumnName, forwardKey);
							continue;
						}

						logger.debug("{}[{}] forward: loaded from table={} via {}.{}={}: referenced IDLMAware={}",
								currentTableName, currentRecordId, forwardTableName, currentTableName, forwardColumnName, forwardKey, forwardRecord);

						result.add(forwardReference, forwardRecord.getDLM_Partition_ID());
						if (forwardRecord.getDLM_Partition_ID() > 0)
						{
							// log why we do not search further using the new found foreign record
							logger.debug("{}[{}] forward: referenced IDLMAware={} already has DLM_Partition_ID={}",
									currentTableName, currentRecordId, forwardRecord, forwardRecord.getDLM_Partition_ID());
						}
					}
				}
			}

			// Look BACKWARD
			//
			final List<PartitionerConfigReference> backwardRefs = config.getReferences(currentTableName);
			for (final PartitionerConfigReference backwardRef : backwardRefs)
			{
				final PartitionerConfigLine backwardLine = backwardRef.getParent();
				final String backwardTableName = backwardLine.getTableName().toLowerCase(); // toLowerCase to avoid trouble if a config was just augmented from a DLMException.
				final String backwardColumnName = backwardRef.getReferencingColumnName();

				// load all records which reference foreignRecord
				// don't excluded records with DLM_Partition_ID>0 becase we might need to merge them into the partition we are currently building
				final IQueryBuilder<IDLMAware> queryBuilder = Services.get(IQueryBL.class)
						.createQueryBuilder(IDLMAware.class, backwardTableName, ctxAware)
						.addEqualsFilter(backwardRef.getReferencingColumnName(), currentRecordId);

				// if we have a case of AD_Table_ID/Record_ID,
				// then we need to make sure to only load records whose AD_Table_ID references currentRecord
				if (columnBL.isRecordColumnName(backwardColumnName))
				{
					// note that referencedTableColumnName = AD_Table_ID, in most cases
					final String referencedTableColumnName = columnBL.getTableColumnName(backwardTableName, backwardRef.getReferencingColumnName())
							.orElseThrow(Check.supplyEx("Table={} has no table column name for recordColumnName={}", backwardTableName, backwardColumnName));

					final int referencedTableID = adTableDAO.retrieveTableId(currentTableName);

					queryBuilder.addEqualsFilter(referencedTableColumnName, referencedTableID);
				}

				// avoid loading the records we already added before.
				// This doesn't work well. NOT IN is probably not very performant, see
				// http://stackoverflow.com/questions/7125291/postgresql-not-in-versus-except-performance-difference-edited-2
				// maybe it would become better when we do something along the lines of
				// https://www.datadoghq.com/blog/100x-faster-postgres-performance-by-changing-1-line/
				// at any rate it looks the postgresql jdbc driver "only" supports prepared statements with 65536 parameters,
				// Solutions:
				// 1. don't try to exclude anything and live with records beeing returned that we already saw earlier
				// 2. store the partition more regularly and add some sort of negative left-join to the query
				// {
				// final String columnName = columnBL.getSingleKeyColumn(backwardTableName);
				// final Collection<Integer> alreadyAddedBackwardIds = result.getIds(backwardTableName);
				//
				// queryBuilder.addNotInArrayFilter(columnName, alreadyAddedBackwardIds);
				// }

				// de.metas.dlm.partitioner.PartitionerTools.dumpHistogram(result)
				final List<IDLMAware> backwardRecords = queryBuilder
						.create()
						.list();

				for (final IDLMAware backwardRecord : backwardRecords)
				{
					InterfaceWrapperHelper.setTrxName(backwardRecord, ctxAware.getTrxName()); // we need this for MPinstance, because it explicitly ignores the trx it is loaded with in its constructor.

					final ITableRecordReference backwardTableRecordReference = ITableRecordReference.FromModelConverter.convert(backwardRecord);

					final boolean recordWasNotYetAddedBefore = result.add(backwardTableRecordReference, backwardRecord.getDLM_Partition_ID());

					// should not happen because we excluded alreadyAddedBackwardIds from the loading query
					// Check.errorUnless(recordWasNotYetAddedBefore, "{}[{}] backward: WAS ALREADY ADDED! - loaded referencing IDLMAware={} from table={} via {}.{}={}",
					// currentTableName, currentRecordId, backwardRecord, backwardTableName, backwardTableName, backwardColumnName, currentRecordId);
					if (recordWasNotYetAddedBefore)
					{
						// the foreign record was not yet added before. Add it now
						logger.debug("{}[{}] backward: loaded from table={} via {}.{}={}: referencing IDLMAware={}",
								currentTableName, currentRecordId, backwardTableName, backwardTableName, backwardColumnName, currentRecordId, backwardRecord);

						if (backwardRecord.getDLM_Partition_ID() > 0)
						{
							// log why we did not search further using the new found foreign record
							logger.debug("{}[{}] backward: referenced IDLMAware={} already has DLM_Partition_ID={}",
									currentTableName, currentRecordId, backwardRecord, backwardRecord.getDLM_Partition_ID());
						}
					}
					else
					{
						logger.trace("{}[{}] backward: ITableRecordReference={} was already added in a previous iteration. Returning", currentTableName, currentRecordId, backwardTableRecordReference);
					}
				}
			}
		}
		return result;
	}

	private boolean shallStoreResult(final IterateResult result)
	{
		// return true;
		final int maxSize = 100000;
		return result.size() > maxSize;
	}

	private void storeOutOfTrx(final PartitionerConfig newConfig)
	{
		Services.get(ITrxManager.class).run(new TrxRunnable()
		{
			@Override
			public void run(final String localTrxName) throws Exception
			{
				storePartitionConfig(newConfig);
			}
		});
	}

	private void checkIfTableIsDLM(final String tableName, final OnNotDLMTable onNotDLMTable)
	{
		if (Adempiere.isUnitTestMode())
		{
			return;
		}

		final ITrxManager trxManager = Services.get(ITrxManager.class);

		final IQueryBL queryBL = Services.get(IQueryBL.class);

		// don't use IADTableDAO, because in this particular case, we don't want the table's trxName to be "NONE"
		final I_AD_Table referencingTable = queryBL.createQueryBuilder(I_AD_Table.class, Env.getCtx(), ITrx.TRXNAME_ThreadInherited)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(org.compiere.model.I_AD_Table.COLUMNNAME_TableName, tableName, UpperCaseQueryFilterModifier.instance)
				.create()
				.firstOnly(I_AD_Table.class);
		Check.errorIf(referencingTable == null, "I_AD_Table record for tableName={} is null", tableName); // this can happen in unit test mode

		if (referencingTable.isDLM())
		{
			return; // nothing to do
		}

		if (onNotDLMTable == OnNotDLMTable.FAIL)
		{
			throw new TableNotAddedToDLMException(referencingTable);
		}

		//
		// then the the table, and if necessary, DLM it.
		//
		trxManager.run(new TrxRunnable()
		{
			@Override
			public void run(final String localTrxName) throws Exception
			{
				final IDLMService dlmService = Services.get(IDLMService.class);

				if (!referencingTable.isDLM())
				{
					final String msg = "ReferencingTable={} is not yet DLM'ed; doing it now";
					logger.info(msg, tableName);
					ILoggable.THREADLOCAL.getLoggable().addLog(msg, tableName);

					dlmService.addTableToDLM(referencingTable);
				}
			}
		});
	}

	@Override
	public Partition storePartition(final Partition partition, final boolean outOfTrx)
	{
		if (outOfTrx)
		{
			final Mutable<Partition> result = new Mutable<>();

			Services.get(ITrxManager.class).run(new TrxRunnable()
			{
				@Override
				public void run(final String localTrxName) throws Exception
				{
					result.setValue(storePartition0(partition));
				}
			});
			return result.getValue();
		}

		return storePartition0(partition);
	}

	private Partition storePartition0(final Partition partition)
	{
		final PlainContextAware ctxAware = PlainContextAware.newWithThreadInheritedTrx(Env.getCtx());

		//
		// create and save the new partition record
		final I_DLM_Partition partitionDB;
		if (partition.getDLM_Partition_ID() > 0)
		{
			partitionDB = InterfaceWrapperHelper.create(Env.getCtx(), partition.getDLM_Partition_ID(), I_DLM_Partition.class, ITrx.TRXNAME_ThreadInherited);
		}
		else
		{
			partitionDB = InterfaceWrapperHelper.newInstance(I_DLM_Partition.class, ctxAware);
		}

		if (partition.isConfigChanged())
		{
			final PartitionerConfig storedConfig = storePartitionConfig(partition.getConfig());
			partitionDB.setDLM_Partition_Config_ID(storedConfig.getDLM_Partition_Config_ID());
		}

		partitionDB.setCurrent_DLM_Level(partition.getCurrentDLMLevel());
		partitionDB.setTarget_DLM_Level(partition.getTargetDLMLevel());
		partitionDB.setDateNextInspection(partition.getNextInspectionDate());
		partitionDB.setIsPartitionComplete(partition.isComplete());

		InterfaceWrapperHelper.save(partitionDB);

		// use intermediatePartition from here onwards.
		Partition intermediatePartition = partition.withDLM_Partition_ID(partitionDB.getDLM_Partition_ID());

		if (intermediatePartition.isRecordsChanged())
		{
			// update the partitioned data. note that here, intermediatePartition needs to have a DLM_Partition_ID
			final boolean recordsAdded = storeRecordsPartitionID(ctxAware, intermediatePartition);
			if (recordsAdded)
			{
				intermediatePartition.getRecordsFlat().forEach(tableRefordRef -> {
					tableRefordRef.notifyModelStaled();
				});

				// the coordinator needs to reevaluate
				partitionDB.setTarget_DLM_Level(IMigratorService.DLM_Level_NOT_SET);
				partitionDB.setDateNextInspection(null);
				InterfaceWrapperHelper.save(partitionDB);

				intermediatePartition = intermediatePartition
						.withNextInspectionDate(null)
						.withTargetDLMLevel(IMigratorService.DLM_Level_NOT_SET);
			}
		}

		final String msg = "Stored the partition {} with {} records; configChanged={}; recordsChanged={}";
		final Object[] msgParameters = { partitionDB, intermediatePartition.getRecordsFlat().size(), intermediatePartition.isConfigChanged(), intermediatePartition.isRecordsChanged() };
		logger.info(msg, msgParameters);
		ILoggable.THREADLOCAL.getLoggable().addLog(msg, msgParameters);

		final Partition result = intermediatePartition.withJustStored(partitionDB.getDLM_Partition_ID());
		return result;
	}

	@Override
	public Partition loadPartition(final I_DLM_Partition partitionDB)
	{
		// load the records using our view
		final Map<String, Collection<ITableRecordReference>> records = Collections.emptyMap();

		// load the config
		final PartitionerConfig config = loadPartitionConfig(partitionDB.getDLM_Partition_Config());

		final int targetDLMLevel = partitionDB.getTarget_DLM_Level();
		final int currentDLMLevel = partitionDB.getCurrent_DLM_Level();
		final Timestamp dateNextInspection = partitionDB.getDateNextInspection();
		final boolean partitionComplete = partitionDB.isPartitionComplete();
		return Partition.loadedPartition(
				config,
				records,
				partitionComplete,
				currentDLMLevel,
				targetDLMLevel,
				dateNextInspection,
				partitionDB.getDLM_Partition_ID());
	}

	@Override
	public PartitionerConfig storePartitionConfig(final PartitionerConfig config)
	{
		final IADTableDAO adTableDAO = Services.get(IADTableDAO.class);

		I_DLM_Partition_Config configDB;

		final PlainContextAware ctxAware = PlainContextAware.newWithThreadInheritedTrx(Env.getCtx());

		if (config.getDLM_Partition_Config_ID() > 0)
		{
			// load existing DLM_Partition_Config record
			configDB = InterfaceWrapperHelper.create(ctxAware.getCtx(), config.getDLM_Partition_Config_ID(), I_DLM_Partition_Config.class, ctxAware.getTrxName());
		}
		else
		{
			// new DLM_Partition_Config record
			configDB = InterfaceWrapperHelper.newInstance(I_DLM_Partition_Config.class, ctxAware);
		}

		if (!Check.isEmpty(config.getName(), true))
		{
			configDB.setName(config.getName());
		}

		InterfaceWrapperHelper.save(configDB);
		config.setDLM_Partition_Config_ID(configDB.getDLM_Partition_Config_ID());

		// we first need to persist only the lines,
		// so that we can be sure to later have all the IDs for DLM_Partition_Config_Reference.DLM_Partition_Config_Reference_ID
		for (final PartitionerConfigLine line : config.getLines())
		{
			I_DLM_Partition_Config_Line configLineDB;
			if (line.getDLM_Partition_Config_Line_ID() > 0)
			{
				// load existing DLM_Partition_Config_Line record
				configLineDB = InterfaceWrapperHelper.create(ctxAware.getCtx(), line.getDLM_Partition_Config_Line_ID(), I_DLM_Partition_Config_Line.class, ctxAware.getTrxName());
			}
			else
			{
				// new DLM_Partition_Config_Line record
				configLineDB = InterfaceWrapperHelper.newInstance(I_DLM_Partition_Config_Line.class, ctxAware);
			}
			configLineDB.setDLM_Partition_Config(configDB);

			final int referencingTableID = adTableDAO.retrieveTableId(line.getTableName());
			configLineDB.setDLM_Referencing_Table_ID(referencingTableID);
			InterfaceWrapperHelper.save(configLineDB);
			line.setDLM_Partition_Config_Line_ID(configLineDB.getDLM_Partition_Config_Line_ID());
		}

		for (final PartitionerConfigLine line : config.getLines())
		{
			for (final PartitionerConfigReference ref : line.getReferences())
			{
				I_DLM_Partition_Config_Reference configRefDB;
				if (ref.getDLM_Partition_Config_Reference_ID() > 0)
				{
					// load existing DLM_Partition_Config_Reference record
					configRefDB = InterfaceWrapperHelper.create(ctxAware.getCtx(), ref.getDLM_Partition_Config_Reference_ID(), I_DLM_Partition_Config_Reference.class, ctxAware.getTrxName());
				}
				else
				{
					// new DLM_Partition_Config_Reference record
					configRefDB = InterfaceWrapperHelper.newInstance(I_DLM_Partition_Config_Reference.class, ctxAware);
				}
				configRefDB.setDLM_Partition_Config_Line_ID(line.getDLM_Partition_Config_Line_ID());

				final int referencedTableID = adTableDAO.retrieveTableId(ref.getReferencedTableName());
				configRefDB.setDLM_Referenced_Table_ID(referencedTableID);

				final I_AD_Column referencingColumn = adTableDAO.retrieveColumn(line.getTableName(), ref.getReferencingColumnName());
				configRefDB.setDLM_Referencing_Column(referencingColumn);

				InterfaceWrapperHelper.save(configRefDB);
				ref.setDLM_Partition_Config_Reference_ID(configRefDB.getDLM_Partition_Config_Reference_ID());
			}
		}
		final String msg = "Stored DLM_Partition_Config={}";
		logger.info(msg, configDB);
		ILoggable.THREADLOCAL.getLoggable().addLog(msg, configDB);

		return PartitionerConfig.builder(config)
				.setChanged(false)
				.build();
	}

	@Override
	public PartitionerConfig loadDefaultPartitionerConfig()
	{
		final I_DLM_Partition_Config defaultConfigDB = Services.get(IQueryBL.class).createQueryBuilder(I_DLM_Partition_Config.class, PlainContextAware.newOutOfTrx(Env.getCtx()))
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_DLM_Partition_Config.COLUMNNAME_IsDefault, true)
				.create()
				.firstOnly(I_DLM_Partition_Config.class); // note that we have a UC, so firstonly is OK

		return loadPartitionConfig(defaultConfigDB);
	}

	@Override
	public PartitionerConfig loadPartitionConfig(final I_DLM_Partition_Config configDB)
	{
		if (configDB == null)
		{
			return new PartitionerConfig.Builder().setName("empty config").build();
		}

		final PlainContextAware ctxAware = PlainContextAware.newWithThreadInheritedTrx(Env.getCtx());

		final Builder configBuilder = PartitionerConfig.builder()
				.setName(configDB.getName())
				.setDLM_Partition_Config_ID(configDB.getDLM_Partition_Config_ID());

		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final List<I_DLM_Partition_Config_Line> lines = queryBL.createQueryBuilder(I_DLM_Partition_Config_Line.class, ctxAware)
				.addOnlyActiveRecordsFilter()
				.addEqualsFilter(I_DLM_Partition_Config_Line.COLUMN_DLM_Partition_Config_ID, configDB.getDLM_Partition_Config_ID())
				.create()
				.list();

		for (final I_DLM_Partition_Config_Line line : lines)
		{
			final LineBuilder lineBuilder = configBuilder
					.line(line.getDLM_Referencing_Table().getTableName())
					.setDLM_Partition_Config_Line(line.getDLM_Partition_Config_Line_ID());

			final List<I_DLM_Partition_Config_Reference> refs = queryBL.createQueryBuilder(I_DLM_Partition_Config_Reference.class, ctxAware)
					.addOnlyActiveRecordsFilter()
					.addEqualsFilter(I_DLM_Partition_Config_Reference.COLUMN_DLM_Partition_Config_Line_ID, line.getDLM_Partition_Config_Line_ID())
					.create()
					.list();

			for (final I_DLM_Partition_Config_Reference ref : refs)
			{
				final RefBuilder refBuilder = lineBuilder.ref()
						.setReferencedTableName(ref.getDLM_Referenced_Table().getTableName())
						.setReferencingColumnName(ref.getDLM_Referencing_Column().getColumnName())
						.setDLM_Partition_Config_Reference_ID(ref.getDLM_Partition_Config_Reference_ID());

				refBuilder.endRef();
			}
			lineBuilder.endLine();
		}

		final PartitionerConfig config = configBuilder.build();
		return config;
	}

	@Override
	public final PartitionerConfig augmentPartitionerConfig(final PartitionerConfig config,
			final List<TableReferenceDescriptor> descriptors)
	{
		final Builder builder = PartitionerConfig.builder(config);

		descriptors.forEach(descriptor -> {

			final String referencingTableName = descriptor.getReferencingTableName();
			final String referencingColumnName = descriptor.getReferencingColumnName();
			final String referencedTableName = descriptor.getReferencedTableName();

			if (!config.isMissing(descriptor))
			{
				return; // nothing to do
			}

			builder
					.setChanged(true)
					.line(referencingTableName)
					.ref().setReferencingColumnName(referencingColumnName).setReferencedTableName(referencedTableName)
					.endRef()
					.endLine();

			final String msg = "Added descriptor={} to the config with name={}";
			final Object[] params = { descriptor, config.getName() };
			logger.info(msg, params);
			ILoggable.THREADLOCAL.getLoggable().addLog(msg, params);
		});

		return builder.build();
	}

	@Override
	public ITemporaryConnectionCustomizer createConnectionCustomizer()
	{
		final int dlmLevel = IMigratorService.DLM_Level_TEST; // don't set it to 0, because otherwise, records will vanish from the partitionerService's radar after it successfully invoked testMigratePartition
		final int dlmCoalesceLevel = IMigratorService.DLM_Level_LIVE; // records that were not yet given a DLM-Level shall be assumed to be "operational"
		final DLMConnectionCustomizer connectionCustomizer = new DLMConnectionCustomizer(dlmLevel, dlmCoalesceLevel);

		return connectionCustomizer;
	}

	@VisibleForTesting
	/* package */ IterateResult attachToPartition(
			final IterateResult initialResult,
			final PartitionerConfig config)
	{
		final PlainContextAware ctxAware = PlainContextAware.newWithThreadInheritedTrx(Env.getCtx());

		final IterateResult result = iterate(
				config,
				ctxAware,
				initialResult);

		logger.info("Found {} records via config.name={}", result.size(), config.getName());

		storeIterateResult(config, result, ctxAware);
		return result;
	}

	/**
	 * Persists the given <code>result</code> to DB and invokes {@link IterateResult#clearAfterPartitionStored()} to release memory.
	 *
	 * @param config
	 * @param result side-effect: the method will call {@link IterateResult#clearAfterPartitionStored(int)}
	 * @param ctxAware
	 * @return
	 */
	private void storeIterateResult(final PartitionerConfig config,
			final IterateResult result,
			final IContextAware ctxAware)
	{
		if (result.getQueueRecordsToDelete().isEmpty() && result.getQueueRecordsToStore().isEmpty() && result.size() == 0)
		{
			// this can happen if a DLM_Partition_Record has IsCompletePartition=N but still doesn't have any workqueue records,
			// or if the respective referenced records were deleted meanwhile.
			// or if for a given work queue queue, there where no additional records found.
			// TODO: log
			return;
		}

		final Partition resultPartition = result.getPartition()
				.withConfig(config)
				.withComplete(result.isQueueEmpty())
				.withRecords(result.getTableName2Record());

		final Partition storedPartition;
		final Map<Integer, Set<ITableRecordReference>> dlmPartitionId2Record = result.getDlmPartitionId2Record();

		if (dlmPartitionId2Record.isEmpty())
		{
			logger.debug("Result={} has no records; config={}", result, config);
			storedPartition = storePartition(
					resultPartition,
					true);
		}
		else if (dlmPartitionId2Record.size() == 1)
		{
			// if there is just "not set" (i.e. <= 0), then create a new partition
			final Integer singleKey = dlmPartitionId2Record.keySet().iterator().next();

			if (singleKey <= 0 && result.getPartition().getDLM_Partition_ID() > 0)
			{
				// the records in "result" don't yet have a DLM_Partition_ID, but their "siblings" were already stored earlier to a partiton, so we sore them in that same partition
				storedPartition = storePartition(
						resultPartition,
						true);
			}
			else
			{
				// in unit test mode, singleKey might be -1, see POJOWrapper.DEFAULT_VALUE_ID
				Check.errorUnless((singleKey <= -1 && resultPartition.getDLM_Partition_ID() <= 0) ||
						singleKey == resultPartition.getDLM_Partition_ID(),
						"singleKey={} needs to be the same as result.getDlmPartitionId()={}; result={}",
						singleKey, resultPartition.getDLM_Partition_ID(), result);

				storedPartition = storePartition(
						resultPartition
								.withDLM_Partition_ID(singleKey),
						true);
			}
		}
		else // dlmPartitionId2Record.size() > 1
		{
			// if there are multiple IDs, then merge the partitions: load all records for all different DLM_Partitons, pick one DLM_Partition_ID and add all records that DLM_Partition_ID.
			final Integer firstKey = dlmPartitionId2Record
					.keySet().stream()
					.filter(dlmPartitionId -> dlmPartitionId > 0) // we want the first partition ID that is "persisted" in the DB
					.findFirst()
					.orElseThrow(Check.supplyEx("We have more than one DLM_Partition_IDs, but none of them >0. DLM_Partition_ID={} ", dlmPartitionId2Record.keySet()));

			final IDLMService dlmService = Services.get(IDLMService.class);

			dlmPartitionId2Record.keySet().stream()

					// no point loading all the records that already have the DLM_Partition_ID we want to update our records to
					.filter(dlmPartitionId -> dlmPartitionId != firstKey)

					// no point attempting to load the records for DLM_Partition_ID=0
					.filter(dlmPartitionId -> dlmPartitionId > 0)

					// for each partition ID, update the records that reference it to now reference "our" partittion
					.forEach(dlmPartitionId -> {
						dlmService.directUpdateDLMColumn(ctxAware, dlmPartitionId, IDLMAware.COLUMNNAME_DLM_Partition_ID, firstKey);

						// we know that the partitition with dlmPartitionId is now empty, so let's update its PartitionSize value
						// note that we don't want to delete it because we don't know that there aren't any DLM_Partition_WorkQueue_records
						final I_DLM_Partition emptyPartitionDB = InterfaceWrapperHelper.create(ctxAware.getCtx(), dlmPartitionId, I_DLM_Partition.class, ctxAware.getTrxName());
						emptyPartitionDB.setPartitionSize(0);
						InterfaceWrapperHelper.save(emptyPartitionDB);
					});

			storedPartition = storePartition(
					resultPartition
							.withDLM_Partition_ID(firstKey)
							.withTargetDLMLevel(IMigratorService.DLM_Level_NOT_SET)
							.withNextInspectionDate(null),
					true);
		}

		{
			// rewrite the queue TODO log what was deleted and created
			// maybe in future we can have a more efficient sync
			result.getQueueRecordsToDelete()
					.forEach(r -> {

						// It's not a must to delete them 1-by-1, but we can't just create one chuck with unknow size!
						// If we want to delete more than one at a time, we need to create chucks of a fixed size that is less than 2^32.
						Services.get(IQueryBL.class)
								.createQueryBuilder(I_DLM_Partition_Workqueue.class, ctxAware)
								.addEqualsFilter(I_DLM_Partition_Workqueue.COLUMN_DLM_Partition_Workqueue_ID, r.getDLM_Partition_Workqueue_ID())
								.create()
								.deleteDirectly();
					});

			result.getQueueRecordsToStore()
					.forEach(r -> {

						final ITableRecordReference tableRecordReference = r.getTableRecordReference();

						final I_DLM_Partition_Workqueue newQueueRecord = InterfaceWrapperHelper.newInstance(I_DLM_Partition_Workqueue.class);
						newQueueRecord.setDLM_Partition_ID(storedPartition.getDLM_Partition_ID());
						newQueueRecord.setAD_Table_ID(tableRecordReference.getAD_Table_ID());
						newQueueRecord.setRecord_ID(tableRecordReference.getRecord_ID());

						InterfaceWrapperHelper.save(newQueueRecord);

						r.setDLM_Partition_Workqueue_ID(newQueueRecord.getDLM_Partition_Workqueue_ID());
					});
		}

		result.clearAfterPartitionStored(storedPartition);
	}

	/**
	 * Effectively persists {@link Partition#getRecords()} by updating the respective database records.
	 *
	 * @param ctxAware
	 * @param partition
	 */
	private boolean storeRecordsPartitionID(final IContextAware ctxAware,
			final Partition partition)
	{
		Check.errorIf(partition.getDLM_Partition_ID() <= 0, "Partition={} has no DLM_Partition_ID", partition);

		final Map<String, Collection<ITableRecordReference>> table2Record = partition.getRecords();

		final IQueryBL queryBL = Services.get(IQueryBL.class);
		final IColumnBL columnBL = Services.get(IColumnBL.class);

		int updatedSum = 0;

		for (final Entry<String, Collection<ITableRecordReference>> tableWithRecords : table2Record.entrySet())
		{
			final String tableName = tableWithRecords.getKey();
			final String keyColumn = columnBL.getSingleKeyColumn(tableName);

			final Collection<ITableRecordReference> records = tableWithRecords.getValue();
			if (records.isEmpty())
			{
				continue;
			}
			final Integer[] recordIds = records
					.stream()
					.map(r -> r.getRecord_ID())
					.toArray(size -> new Integer[size]);

			final int updated = queryBL.createQueryBuilder(IDLMAware.class, tableName, ctxAware)
					.addInArrayFilter(keyColumn, recordIds)
					.addNotEqualsFilter(IDLMAware.COLUMNNAME_DLM_Partition_ID, partition.getDLM_Partition_ID()) // only records that are not yet in this partition
					.create()
					.updateDirectly()
					.addSetColumnValue(IDLMAware.COLUMNNAME_DLM_Partition_ID, partition.getDLM_Partition_ID())
					.execute();
			// make sure we didn't update more than we had on the screen..just to be sure
			Check.errorIf(updated > recordIds.length, "We attempted to update {} record(s) of table {} to {}={}, but instead we updated {} records",
					recordIds.length, tableName, IDLMAware.COLUMNNAME_DLM_Partition_ID, partition.getDLM_Partition_ID(), updated);

			updatedSum += updated;

			logger.debug("Table {}: updated {} record(s) to {}={} (but not yet committed!)", tableName, updated, IDLMAware.COLUMNNAME_DLM_Partition_ID, partition.getDLM_Partition_ID());
		}

		if (Adempiere.isUnitTestMode())
		{
			// in unit test mode we explicitly need to create I_DLM_Partition_Records.
			// in "normal" mode, DLM_Partition_Record is a view.
			partition.getRecordsFlat().forEach(r -> {

				final boolean match = Services.get(IQueryBL.class).createQueryBuilder(I_DLM_Partition_Record.class, ctxAware)
						.addEqualsFilter(I_DLM_Partition_Record.COLUMNNAME_DLM_Partition_ID, partition.getDLM_Partition_ID())
						.addEqualsFilter(I_DLM_Partition_Record.COLUMNNAME_AD_Table_ID, r.getAD_Table_ID())
						.addEqualsFilter(I_DLM_Partition_Record.COLUMNNAME_Record_ID, r.getRecord_ID())
						.create()
						.match();
				if (!match)
				{
					final I_DLM_Partition_Record viewRecord = InterfaceWrapperHelper.newInstance(I_DLM_Partition_Record.class, ctxAware);
					viewRecord.setAD_Table_ID(r.getAD_Table_ID());
					viewRecord.setRecord_ID(r.getRecord_ID());
					viewRecord.setDLM_Partition_ID(partition.getDLM_Partition_ID());
					InterfaceWrapperHelper.save(viewRecord);
				}
			});
		}

		return updatedSum > 0;
	}

	/**
	 * This method is intedet for unit testing only!
	 * Please don't use it in production, trying to load all records at once might blow your memory.
	 *
	 * @param partition
	 * @return
	 */
	@VisibleForTesting
	/* package */ Partition loadWithAllRecords(Partition partition)
	{
		if (partition.getDLM_Partition_ID() <= 0)
		{
			return partition; // nothing to load
		}

		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final I_DLM_Partition partitionDB = InterfaceWrapperHelper.create(Env.getCtx(), partition.getDLM_Partition_ID(), I_DLM_Partition.class, ITrx.TRXNAME_ThreadInherited);
		final Partition loadedPartition = loadPartition(partitionDB);

		final Map<String, Collection<ITableRecordReference>> allRecords = new HashMap<>();

		final Map<String, List<ITableRecordReference>> collect = queryBL.createQueryBuilder(I_DLM_Partition_Record.class, PlainContextAware.newWithThreadInheritedTrx())
				.addEqualsFilter(I_DLM_Partition_Record.COLUMN_DLM_Partition_ID, partitionDB.getDLM_Partition_ID())
				.create()
				.list()
				.stream()
				.map(r -> ITableRecordReference.FromReferencedModelConverter.convert(r))
				.collect(Collectors.groupingBy(r -> r.getTableName()));

		collect.entrySet().stream()
				.forEach(e -> allRecords.put(e.getKey(), e.getValue()));

		return loadedPartition.withRecords(allRecords);
	}

	Iterator<WorkQueue> loadForTable(Partition partition, final String tableName)
	{
		final IQueryBL queryBL = Services.get(IQueryBL.class);

		final int tableId = Services.get(IADTableDAO.class).retrieveTableId(tableName);

		final Iterator<I_DLM_Partition_Record> map = queryBL.createQueryBuilder(I_DLM_Partition_Record.class, PlainContextAware.newWithThreadInheritedTrx())
				.addEqualsFilter(I_DLM_Partition_Record.COLUMN_DLM_Partition_ID, partition.getDLM_Partition_ID())
				.addEqualsFilter(I_DLM_Partition_Record.COLUMN_AD_Table_ID, tableId)
				.create()
				.setOption(IQuery.OPTION_GuaranteedIteratorRequired, true)
				.setOption(IQuery.OPTION_IteratorBufferSize, 5000)
				.iterate(I_DLM_Partition_Record.class);

		return new Iterator<WorkQueue>()
		{
			@Override
			public boolean hasNext()
			{
				return map.hasNext();
			}

			@Override
			public WorkQueue next()
			{
				return WorkQueue.of(ITableRecordReference.FromReferencedModelConverter.convert(map.next()));
			}
		};
	}
}
