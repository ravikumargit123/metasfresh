package de.metas.report;

import org.adempiere.ad.service.ITaskExecutorService;
import org.adempiere.exceptions.AdempiereException;
import org.adempiere.util.Check;
import org.adempiere.util.FileUtils;
import org.adempiere.util.Loggables;
import org.adempiere.util.Services;
import org.adempiere.util.lang.impl.TableRecordReference;
import org.compiere.print.JRReportViewerProvider;
import org.compiere.util.Ini;
import org.compiere.util.Util;
import org.slf4j.Logger;

import com.google.common.io.Files;

import de.metas.adempiere.report.jasper.OutputType;
import de.metas.document.engine.IDocument;
import de.metas.document.engine.IDocumentBL;
import de.metas.logging.LogManager;
import de.metas.print.IPrintService;
import de.metas.print.IPrintServiceRegistry;
import de.metas.process.ClientOnlyProcess;
import de.metas.process.JavaProcess;
import de.metas.process.ProcessExecutionResult;
import de.metas.process.ProcessInfo;
import de.metas.report.ExecuteReportStrategy.ExecuteReportResult;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.NonNull;
import lombok.Value;

/*
 * #%L
 * de.metas.adempiere.adempiere.base
 * %%
 * Copyright (C) 2018 metas GmbH
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

@ClientOnlyProcess
public abstract class ReportStarter extends JavaProcess
{
	// services
	private static final Logger log = LogManager.getLogger(ReportStarter.class);

	private static JRReportViewerProvider swingJRReportViewerProvider;

	private static JRReportViewerProvider viewerProvider = null;

	private final transient ITaskExecutorService taskExecutorService = Services.get(ITaskExecutorService.class);
	private final transient IPrintServiceRegistry printServiceRegistry = Services.get(IPrintServiceRegistry.class);

	protected abstract ExecuteReportStrategy getExecuteReportStrategy();

	/**
	 * Start Jasper reporting process. Based on {@link ProcessInfo#isPrintPreview()}, it will:
	 * <ul>
	 * <li>directly print the report
	 * <li>will open the report viewer and it will display the report
	 * </ul>
	 */
	@Override
	protected final String doIt() throws Exception
	{
		final ProcessInfo pi = getProcessInfo();

		final ReportPrintingInfo reportPrintingInfo = extractReportPrintingInfo(pi);

		if (reportPrintingInfo.isPrintPreview())
		{
			// Create report and preview
			startProcessPrintPreview(reportPrintingInfo);
		}
		else
		{
			// Create report and print it directly
			if (reportPrintingInfo.isForceSync())
			{
				// gh #1160 if the caller want ou to execute synchronously, then do just that
				startProcess0(pi, reportPrintingInfo);
			}
			else
			{
				// task 08283: direct print can be done in background; no need to let the user wait for this
				taskExecutorService.submit(
						() -> startProcess0(pi, reportPrintingInfo),
						ReportStarter.class.getSimpleName());
			}
		}
		return MSG_OK;
	}

	/**
	 * Set jasper report viewer provider.
	 */
	public static void setNonSwingViewerProvider(@NonNull final JRReportViewerProvider provider)
	{
		viewerProvider = provider;
	}

	public static void setSwingViewerProvider(@NonNull final JRReportViewerProvider provider)
	{
		swingJRReportViewerProvider = provider;
	}

	private void startProcessDirectPrint(final ReportPrintingInfo reportPrintingInfo)
	{
		final ProcessInfo pi = reportPrintingInfo.getProcessInfo();

		final ExecuteReportResult result = getExecuteReportStrategy().executeReport(pi, OutputType.PDF);

		final IPrintService printService = printServiceRegistry.getPrintService();
		printService.print(result, pi);
	}

	private void startProcessPrintPreview(@NonNull final ReportPrintingInfo reportPrintingInfo) throws Exception
	{
		final ProcessInfo processInfo = reportPrintingInfo.getProcessInfo();

		//
		// Get Jasper report viewer provider
		final JRReportViewerProvider jrReportViewerProvider = getJRReportViewerProviderOrNull();
		final OutputType desiredOutputType = jrReportViewerProvider == null ? null : jrReportViewerProvider.getDesiredOutputType();

		//
		// Based on reporting system type, determine: output type
		final ReportingSystemType reportingSystemType = reportPrintingInfo.getReportingSystemType();
		final OutputType outputType;
		switch (reportingSystemType)
		{
			//
			// Jasper reporting
			case Jasper:
			case Other:
				outputType = Util.coalesce(desiredOutputType, processInfo.getJRDesiredOutputType(), OutputType.PDF);
				break;

			//
			// Excel reporting
			case Excel:
				outputType = OutputType.XLS;
				break;

			default:
				throw new AdempiereException("Unknown " + ReportingSystemType.class + ": " + reportingSystemType);
		}

		//
		// Generate report data
		Loggables.get().addLog("ReportStarter.startProcess run report: reportingSystemType={}, title={}, outputType={}", reportingSystemType, processInfo.getTitle(), outputType);
		final ExecuteReportResult result = getExecuteReportStrategy().executeReport(getProcessInfo(), outputType);

		//
		// Set report data to process execution result
		final ProcessExecutionResult processExecutionResult = processInfo.getResult();
		final String reportFilename = extractReportFilename(processInfo, outputType);
		final String reportContentType = outputType.getContentType();
		processExecutionResult.setReportData(result.getReportData(), reportFilename, reportContentType);

		//
		// Print preview (if swing client)
		if (Ini.isClient() && swingJRReportViewerProvider != null)
		{
			swingJRReportViewerProvider.openViewer(result.getReportData(), outputType, processInfo);
		}
	}

	private static final String extractReportFilename(final ProcessInfo pi, final OutputType outputType)
	{
		final String fileBasename = Util.firstValidValue(
				basename -> !Check.isEmpty(basename, true),
				() -> extractReportBasename_IfDocument(pi),
				() -> pi.getTitle(),
				() -> "report_" + pi.getAD_PInstance_ID());

		final String fileExtension = outputType.getFileExtension();

		final String filename = fileBasename.trim() + "." + fileExtension;
		return FileUtils.stripIllegalCharacters(filename);
	}

	private static String extractReportBasename_IfDocument(final ProcessInfo pi)
	{
		final TableRecordReference recordRef = pi.getRecordRefOrNull();
		if (recordRef == null)
		{
			return null;
		}

		final Object record = recordRef.getModel();

		final IDocumentBL documentBL = Services.get(IDocumentBL.class);
		final IDocument document = documentBL.getDocumentOrNull(record);
		if (document == null)
		{
			return null;
		}

		return document.getDocumentInfo();
	}

	private ReportPrintingInfo extractReportPrintingInfo(@NonNull final ProcessInfo pi)
	{
		final ReportPrintingInfo.ReportPrintingInfoBuilder info = ReportPrintingInfo.builder();
		info.processInfo(pi);
		info.printPreview(pi.isPrintPreview());
		info.forceSync(!pi.isAsync()); // gh #1160 if the process info says "sync", then sync it is

		//
		// Determine the ReportingSystem type based on report template file extension
		// TODO: make it more general and centralized with the other reporting code
		final String reportFileExtension = pi
				.getReportTemplate()
				.map(reportTemplate -> Files.getFileExtension(reportTemplate).toLowerCase())
				.orElse(null);

		if ("jasper".equalsIgnoreCase(reportFileExtension)
				|| "jrxml".equalsIgnoreCase(reportFileExtension))
		{
			info.reportingSystemType(ReportingSystemType.Jasper);
		}
		else if ("xls".equalsIgnoreCase(reportFileExtension))
		{
			info.reportingSystemType(ReportingSystemType.Excel);
			info.printPreview(true); // TODO: atm only print preview is supported
		}
		else
		{
			info.reportingSystemType(ReportingSystemType.Other);
		}
		return info.build();
	}

	/**
	 *
	 * @return {@link JRReportViewerProvider} or null
	 */
	private JRReportViewerProvider getJRReportViewerProviderOrNull()
	{
		if (Ini.isClient())
		{
			return swingJRReportViewerProvider;
		}
		else
		{
			return viewerProvider;
		}
	}

	private void startProcess0(final ProcessInfo pi, final ReportPrintingInfo reportPrintingInfo)
	{
		try
		{
			log.info("Doing direct print without preview: {}", reportPrintingInfo);
			startProcessDirectPrint(reportPrintingInfo);
		}
		catch (final Exception e)
		{
			throw AdempiereException.wrapIfNeeded(e);
		}
	}

	private static enum ReportingSystemType
	{
		Jasper,

		Excel,

		/** May be used no invocation to the jasper service is done */
		Other
	};

	@Value
	@Builder
	private static final class ReportPrintingInfo
	{
		ProcessInfo processInfo;
		ReportingSystemType reportingSystemType;
		boolean printPreview;

		/**
		 * Even if {@link #isPrintPreview()} is {@code false}, we do <b>not</b> print in a background thread, if this is false.
		 */
		@Default
		boolean forceSync = false;
	}
}