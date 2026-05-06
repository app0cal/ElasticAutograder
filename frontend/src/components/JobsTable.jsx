import { downloadResults } from "../api/download_file";
import { useMemo } from "react";
import { AgGridProvider, AgGridReact } from "ag-grid-react";
import { AllCommunityModule } from "ag-grid-community";
import { themeQuartz } from "ag-grid-community";

const theme = themeQuartz.withParams({
  backgroundColor: "#1f2836",
  browserColorScheme: "dark",
  chromeBackgroundColor: {
    ref: "foregroundColor",
    mix: 0.07,
    onto: "backgroundColor"
  },
  foregroundColor: "#FFF",
  headerFontSize: 14
});

export default function JobsTable({ jobs, graders, gridRef, onViewDetails }) {
  const graderLabelMap = useMemo(() => {
    const map = new Map();

    for (const grader of graders ?? []) {
      map.set(grader.key, grader.label);
    }

    return map;
  }, [graders]);

  const colDefs = useMemo(
    () => [
      {
        field: "id",
        headerName: "ID",
        cellDataType: "number",
        width: 75,
        flex: 0,
        cellRenderer: ({ value, data }) => (
          <button
            type="button"
            className="jobs-link-button"
            onClick={() => onViewDetails?.(data)}
            title={`Open job ${value}`}
          >
            #{value}
          </button>
        )
      },
      {
        field: "graderType",
        headerName: "Grader Type",
        valueFormatter: ({ value }) => graderLabelMap.get(value) ?? value ?? ""
      },
      {
        field: "originalFilename",
        headerName: "Filename",
        valueFormatter: ({ value }) => value ?? ""
      },
      {
        field: "status",
        headerName: "Status",
        width: 145,
        flex: 0,
        cellRenderer: ({ value }) => {
          const status = value ?? "";
          return <span className={`status-pill status-${status.toLowerCase()}`}>{status}</span>;
        }
      },
      {
        field: "createdAt",
        headerName: "Created At",
        valueFormatter: ({ value }) => (value ? formatDate(value) : ""),
        width: 240,
        flex: 0
      },
      {
        field: "score",
        headerName: "Score",
        cellDataType: "number",
        valueFormatter: ({ value }) => value ?? "",
        width: 85,
        flex: 0
      },
      {
        field: "tests",
        headerName: "Tests",
        valueGetter: ({ data }) => formatTests(data),
        width: 85,
        flex: 0
      },
      {
        headerName: "Actions",
        cellRenderer: (params) => actionsCellRenderer({ ...params, onViewDetails }),
        sortable: false,
        filter: false,
        width: 285,
        flex: 0
      }
    ],
    [graderLabelMap, onViewDetails]
  );

  const rowClassRules = useMemo(
    () => ({
      "job-row-pending": (params) => params.data?.status === "PENDING",
      "job-row-queued": (params) => params.data?.status === "QUEUED",
      "job-row-running": (params) => params.data?.status === "RUNNING",
      "job-row-succeeded": (params) => params.data?.status === "SUCCEEDED",
      "job-row-partial": (params) => params.data?.status === "PARTIAL",
      "job-row-failed": (params) => params.data?.status === "FAILED",
      "job-row-dead-lettered": (params) => params.data?.status === "DEAD_LETTERED",
      "job-row-cancelled": (params) => params.data?.status === "CANCELLED"
    }),
    []
  );

  return (
    <AgGridProvider modules={[AllCommunityModule]}>
      <div>
        <AgGridReact
          ref={gridRef}
          rowData={jobs}
          columnDefs={colDefs}
          rowClassRules={rowClassRules}
          defaultColDef={{ filter: true, flex: 1, floatingFilter: true }}
          gridOptions={{
            theme: theme,
            pagination: true,
            paginationPageSize: 5,
            paginationPageSizeSelector: [5, 10, 25, 100],
            domLayout: "autoHeight"
          }}
          getRowId={({ data }) => String(data.id)}
        />
      </div>
    </AgGridProvider>
  );
}

function formatDate(date) {
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "long",
    day: "numeric",
    hour: "2-digit",
    minute: "numeric",
    second: "numeric"
  }).format(new Date(date));
}

function formatTests(data) {
  return data.testsPassed != null && data.testsTotal != null
    ? `${data.testsPassed} / ${data.testsTotal}`
    : "";
}

function actionsCellRenderer({ data, onViewDetails }) {
  const canDownload = Boolean(data?.resultJson);

  return (
    <div className="jobs-table-actions">
      <button
        type="button"
        className="jobs-table-action-button"
        onClick={() => onViewDetails?.(data)}
      >
        Details
      </button>

      <button
        type="button"
        className="jobs-table-action-button"
        onClick={() => handleDownload(data)}
        disabled={!canDownload}
        title={canDownload ? "Download results.json" : "Results download is available when results exist"}
      >
        Download Results
      </button>
    </div>
  );
}

const handleDownload = async (job) => {
  try {
    const blob = await downloadResults(job.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${job.originalFilename}-results.json`;
    a.click();
    URL.revokeObjectURL(url);
  } catch (err) {
    alert("Could not download results file.");
    throw new Error("Could not download results file.\n" + err);
  }
};
