package org.adempiere.util.lang.impl;

import java.lang.ref.SoftReference;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2015 metas GmbH
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

import java.util.Properties;

import org.adempiere.ad.table.api.IADTableDAO;
import org.adempiere.ad.trx.api.ITrxManager;
import org.adempiere.model.IContextAware;
import org.adempiere.model.InterfaceWrapperHelper;
import org.adempiere.util.Check;
import org.adempiere.util.Services;
import org.adempiere.util.lang.EqualsBuilder;
import org.adempiere.util.lang.HashcodeBuilder;
import org.adempiere.util.lang.ITableRecordReference;
import org.adempiere.util.text.annotation.ToStringBuilder;

import com.google.common.base.Optional;

/**
 * Simple implementation of {@link ITableRecordReference} which can:
 * <ul>
 * <li>wrap an already loaded model (if you use {@link #TableRecordReference(Object)} constructor)
 * <li>start from known AD_Table_ID/Record_ID and will load the underlying model only when it's needed (if you use {@link #TableRecordReference(int, int)} constructor)
 * </ul>
 *
 * TODO: merge logic with {@link org.adempiere.ad.dao.cache.impl.TableRecordCacheLocal}.
 *
 * @author tsa
 *
 */
public final class TableRecordReference implements ITableRecordReference
{
	/**
	 * Creates an {@link ITableRecordReference} from the given model.
	 * <p>
	 * <b>IMPORTANT:</b> this method does not inspect the given model's <code>AD_Table_ID</code> and <code>Record_ID</code> but instead returns just <code>model</code>, wrapped up as
	 * {@link ITableRecordReference}.
	 *
	 * @param model model interface or {@link ITableRecordReference}; <code>null</code> is NOT allowed
	 * @return {@link ITableRecordReference}; never returns null
	 */
	public static final ITableRecordReference of(final Object model)
	{
		if (model instanceof ITableRecordReference)
		{
			return (ITableRecordReference)model;
		}
		return new TableRecordReference(model);
	}

	/**
	 * Same as {@link #of(Object)} but in case <code>model</code> is null then it will return null.
	 *
	 * @param model
	 * @return {@link ITableRecordReference} or null
	 */
	public static final ITableRecordReference ofOrNull(final Object model)
	{
		if (model == null)
		{
			return null;
		}
		return of(model);
	}

	public static ITableRecordReference ofReferenced(final Object model)
	{
		final Optional<Integer> adTableId = InterfaceWrapperHelper.getValue(model, ITableRecordReference.COLUMNNAME_AD_Table_ID);
		final Optional<Integer> recordId = InterfaceWrapperHelper.getValue(model, ITableRecordReference.COLUMNNAME_Record_ID);

		return new TableRecordReference(adTableId.or(-1), recordId.or(-1));
	}

	/**
	 * See {@link ITableRecordReference#FromReferencedModelConverter}.
	 *
	 * @param model
	 * @return
	 */
	public static ITableRecordReference ofReferencedOrNull(final Object model)
	{
		if (model == null)
		{
			return null;
		}
		return ofReferenced(model);
	}

	private final int adTableId;
	private final String tableName;
	private final int recordId;
	@ToStringBuilder(skip = true)
	private Integer _hashcode;

	/**
	 * Cached model. Using a soft reference to avoid memory problems when *a lot* of TableRecordReference are handled.
	 * <p>
	 * Note: when changing this class, please make sure that this member is never <code>null</code>.
	 */
	private transient SoftReference<Object> modelRef = new SoftReference<>(null);

	/**
	 * Creates an instance that will be loaded on demand and is specified by the given <code>adTableId</code> and <code>recordId</code>.
	 * <p>
	 * Hint: Please consider using {@link ITableRecordReference#FromReferencedModelConverter} instead if this constructor.
	 *
	 * @param adTableId
	 * @param recordId
	 */
	public TableRecordReference(final int adTableId, final int recordId)
	{
		Check.assume(adTableId > 0, "adTableId > 0");
		this.adTableId = adTableId;
		this.tableName = Services.get(IADTableDAO.class).retrieveTableName(adTableId);

		Check.assume(recordId > 0, "recordId > 0");
		this.recordId = recordId;
	}

	/**
	 * Creates an instance that will be loaded on demand and is specified by the given <code>tableName</code> and <code>recordId</code>.
	 * <p>
	 * Hint: Please consider using {@link ITableRecordReference#FromReferencedModelConverter} instead if this constructor.
	 *
	 * @param tableName
	 * @param recordId
	 */
	public TableRecordReference(final String tableName, final int recordId)
	{
		Check.assumeNotEmpty(tableName, "tableName not empty");
		this.tableName = tableName;
		this.adTableId = Services.get(IADTableDAO.class).retrieveTableId(tableName);

		Check.assume(recordId > 0, "recordId > 0");
		this.recordId = recordId;
	}

	private TableRecordReference(final Object model)
	{
		Check.assumeNotNull(model, "model not null");
		this.adTableId = InterfaceWrapperHelper.getModelTableId(model);
		this.tableName = InterfaceWrapperHelper.getModelTableName(model);
		this.recordId = InterfaceWrapperHelper.getId(model);

		this.modelRef = new SoftReference<>(model);
	}

	@Override
	public String getTableName()
	{
		return tableName;
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
		{
			return true;
		}

		final TableRecordReference other = EqualsBuilder.getOther(this, obj);
		if (other == null)
		{
			return false;
		}

		return new EqualsBuilder()
				.append(adTableId, other.adTableId)
				// .append(tableName, other.tableName) adTableId alone is sufficient, also tableName can have case differences
				.append(recordId, other.recordId)
				.isEqual();
	}

	@Override
	public int hashCode()
	{
		if (_hashcode == null)
		{
			_hashcode = new HashcodeBuilder()
					.append(adTableId)
					// .append(tableName) adTableId alone is sufficient, also tableName can have case differences
					.append(recordId)
					.toHashcode();
		}
		return _hashcode;
	}

	@Override
	public int getAD_Table_ID()
	{
		return adTableId;
	}

	@Override
	public int getRecord_ID()
	{
		return recordId;
	}

	@Override
	public Object getModel(final IContextAware context)
	{
		checkModelStaled(context);

		//
		// Load the model now
		final Object cachedModel = modelRef.get();
		if (cachedModel != null)
		{
			return cachedModel;
		}

		final Properties ctx = context.getCtx();
		final String trxName = context.getTrxName();
		final Object loadedModel = InterfaceWrapperHelper.create(ctx, tableName, getRecord_ID(), Object.class, trxName);

		modelRef = new SoftReference<>(loadedModel);

		return loadedModel;
	}

	@Override
	public <T> T getModel(final IContextAware context, final Class<T> modelClass)
	{
		return InterfaceWrapperHelper.create(getModel(context), modelClass);
	}

	@Override
	public void notifyModelStaled()
	{
		modelRef = new SoftReference<>(null);
	}

	/**
	 * Checks if underlying (and cached) model is still valid in given context. In case is no longer valid, it will be set to <code>null</code>.
	 *
	 * @param context
	 */
	private void checkModelStaled(final IContextAware context)
	{
		final Object model = modelRef.get();
		if (model == null)
		{
			return;
		}

		final String modelTrxName = InterfaceWrapperHelper.getTrxName(model);
		if (!Services.get(ITrxManager.class).isSameTrxName(modelTrxName, context.getTrxName()))
		{
			modelRef = new SoftReference<>(null);
			return;
		}

		// TODO: why the ctx is not validated, like org.adempiere.ad.dao.cache.impl.TableRecordCacheLocal.getValue(Class<RT>) does?
	}

	@Override
	public String toString()
	{
		final StringBuilder builder = new StringBuilder();
		builder.append("TableRecordReference [tableName=").append(tableName);
		builder.append(", recordId=").append(recordId);
		builder.append(", (SoftReference-)model=").append(modelRef.get());
		builder.append("]");

		return builder.toString();
	}
}
