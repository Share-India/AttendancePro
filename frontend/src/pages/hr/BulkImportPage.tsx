import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert } from '../../components/common/Alert';
import { Icon } from '../../components/common/Icon';
import { PageHeader } from '../../components/common/PageHeader';
import { Spinner } from '../../components/common/Spinner';
import { DataTable, type Column } from '../../components/table/DataTable';
import { ROUTES } from '../../constants/routes';
import { employeeImportApi } from '../../features/dashboard/dashboardApi';
import { ApiError } from '../../types/api';
import type { BulkImportRowError, BulkImportResult } from '../../types/dashboard';

export const BulkImportPage = () => {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [dragging, setDragging] = useState(false);
  const [result, setResult] = useState<BulkImportResult | null>(null);
  const [copied, setCopied] = useState(false);

  const upload = useMutation({
    mutationFn: (chosen: File) => employeeImportApi.upload(chosen),
    onSuccess: (outcome) => {
      setResult(outcome);
      setCopied(false);
      setFile(null);
      if (inputRef.current) {
        inputRef.current.value = '';
      }
      queryClient.invalidateQueries({ queryKey: ['employees'] });
    },
  });

  const downloadTemplate = useMutation({
    mutationFn: () => employeeImportApi.template(),
    onSuccess: (csv) => {
      // Built in the browser rather than linked, so the download carries the
      // caller's token instead of relying on an unauthenticated URL.
      const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'employee-import-template.csv';
      anchor.click();
      URL.revokeObjectURL(url);
    },
  });

  const choose = (chosen: File | null) => {
    setResult(null);
    setFile(chosen);
  };

  const errorColumns: Column<BulkImportRowError>[] = [
    {
      key: 'row',
      header: 'Row',
      className: 'tabular-nums',
      render: (row) => row.rowNumber,
    },
    {
      key: 'code',
      header: 'Employee code',
      render: (row) => (
        <span className="font-mono">{row.employeeCode ?? '—'}</span>
      ),
    },
    { key: 'message', header: 'What went wrong', render: (row) => row.message },
  ];

  return (
    <div className="mx-auto max-w-[960px]">
      <PageHeader
        title="Bulk Import"
        description="Onboard many employees from one CSV. Rows are checked individually, so a few bad rows never cost you the whole file."
        actions={
          <button
            type="button"
            className="btn-secondary"
            onClick={() => downloadTemplate.mutate()}
            disabled={downloadTemplate.isPending}
          >
            {downloadTemplate.isPending ? (
              <Spinner className="h-4 w-4" />
            ) : (
              <Icon name="download" className="text-[18px]" />
            )}
            Download template
          </button>
        }
      />

      {upload.isError && (
        <Alert variant="error" title="The file could not be read" className="mb-lg">
          {upload.error instanceof ApiError ? upload.error.message : 'Please try again.'}
        </Alert>
      )}

      {downloadTemplate.isError && (
        <Alert variant="error" title="Could not fetch the template" className="mb-lg">
          {downloadTemplate.error instanceof ApiError
            ? downloadTemplate.error.message
            : 'Please try again.'}
        </Alert>
      )}

      {result && (
        <Alert
          variant={result.failed === 0 && result.warnings.length === 0 ? 'success' : 'warning'}
          title={
            result.failed === 0
              ? `Imported ${result.imported} employee${result.imported === 1 ? '' : 's'}`
              : `Imported ${result.imported} of ${result.totalRows} rows`
          }
          className="mb-lg"
        >
          {result.failed === 0 ? (
            <>
              {result.warnings.length > 0
                ? `Everyone in the file now has a record, though ${result.warnings.length} needs a detail set by hand. `
                : 'Everyone in the file now has a record. '}
              <Link to={ROUTES.employees} className="underline underline-offset-2">
                Open the directory
              </Link>
              .
            </>
          ) : (
            <>
              {result.failed} row{result.failed === 1 ? '' : 's'} were rejected and are listed
              below. The rest were imported — fix those rows and upload just them again.
            </>
          )}
        </Alert>
      )}

      <section className="card mb-lg p-lg">
        <div
          onDragOver={(event) => {
            event.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={(event) => {
            event.preventDefault();
            setDragging(false);
            const dropped = event.dataTransfer.files?.[0];
            if (dropped) {
              choose(dropped);
            }
          }}
          className={`flex flex-col items-center justify-center gap-sm rounded-DEFAULT border-2 border-dashed p-xl text-center transition-colors ${
            dragging ? 'border-primary bg-primary-fixed' : 'border-outline-variant'
          }`}
        >
          <Icon name="upload_file" className="text-[40px] text-outline" />
          {file ? (
            <>
              <p className="text-body-md text-on-surface">{file.name}</p>
              <p className="text-body-sm text-on-surface-variant">
                {(file.size / 1024).toFixed(1)} KB
              </p>
            </>
          ) : (
            <>
              <p className="text-body-md text-on-surface">Drop a CSV here</p>
              <p className="text-body-sm text-on-surface-variant">
                or choose one from your computer
              </p>
            </>
          )}

          <input
            ref={inputRef}
            type="file"
            accept=".csv,text/csv"
            className="sr-only"
            id="import-file"
            onChange={(event) => choose(event.target.files?.[0] ?? null)}
          />
          <label htmlFor="import-file" className="btn-secondary cursor-pointer">
            <Icon name="folder_open" className="text-[18px]" />
            {file ? 'Choose a different file' : 'Choose file'}
          </label>
        </div>

        <div className="mt-lg flex flex-wrap items-center justify-between gap-md">
          <p className="text-body-sm text-on-surface-variant">
            Each imported employee gets a login and a one-time password. The passwords appear
            below once the import finishes and are never recoverable afterwards, so copy them
            before leaving this page. Nobody is emailed automatically.
          </p>
          <button
            type="button"
            className="btn-primary"
            disabled={!file || upload.isPending}
            onClick={() => file && upload.mutate(file)}
          >
            {upload.isPending ? (
              <Spinner className="h-4 w-4" />
            ) : (
              <Icon name="upload" className="text-[18px]" />
            )}
            Import
          </button>
        </div>
      </section>

      {result && result.errors.length > 0 && (
        <section className="mb-lg">
          <h2 className="mb-md text-title-md text-on-surface">Rows that were rejected</h2>
          <DataTable
            columns={errorColumns}
            rows={result.errors}
            rowKey={(row) => row.rowNumber}
            page={0}
            size={result.errors.length || 1}
            totalElements={result.errors.length}
            totalPages={1}
            onPageChange={() => undefined}
          />
        </section>
      )}

      {result && result.credentials.length > 0 && (
        <section className="mb-lg">
          <div className="mb-sm flex flex-wrap items-center justify-between gap-sm">
            <h2 className="text-title-md text-on-surface">Sign-in details</h2>
            <button
              type="button"
              className="btn-secondary"
              onClick={() => {
                const text = result.credentials
                  .map((c) => `${c.employeeCode}\t${c.employeeName}\t${c.temporaryPassword}`)
                  .join('\n');
                navigator.clipboard?.writeText(text);
                setCopied(true);
              }}
            >
              <Icon name={copied ? 'check' : 'content_copy'} className="text-[18px]" />
              {copied ? 'Copied' : 'Copy all'}
            </button>
          </div>

          <Alert variant="warning" title="This is the only time these are shown" className="mb-md">
            The passwords are stored hashed. Once you leave this page nobody — including an
            administrator — can read them back; the account would have to be reset instead.
          </Alert>

          <div className="card overflow-x-auto">
            <table className="w-full border-collapse text-body-md">
              <thead>
                <tr className="border-b border-outline-variant bg-surface-container-low">
                  <th scope="col" className="px-md py-sm text-left text-label-md uppercase tracking-wide text-on-surface-variant">Employee</th>
                  <th scope="col" className="px-md py-sm text-left text-label-md uppercase tracking-wide text-on-surface-variant">Sign in with</th>
                  <th scope="col" className="px-md py-sm text-left text-label-md uppercase tracking-wide text-on-surface-variant">One-time password</th>
                </tr>
              </thead>
              <tbody>
                {result.credentials.map((credential) => (
                  <tr key={credential.employeeCode} className="border-b border-outline-variant last:border-b-0">
                    <td className="px-md py-sm text-on-surface">{credential.employeeName}</td>
                    <td className="px-md py-sm font-mono text-on-surface">{credential.employeeCode}</td>
                    <td className="px-md py-sm font-mono text-on-surface">
                      {credential.temporaryPassword}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {result && result.warnings.length > 0 && (
        <section>
          <h2 className="mb-sm text-title-md text-on-surface">Imported, with something left undone</h2>
          <p className="mb-md text-body-md text-on-surface-variant">
            These people exist and can sign in. Something alongside them did not stick — usually a
            manager code that matches nobody — so set it on their profile.
          </p>
          <DataTable
            columns={errorColumns}
            rows={result.warnings}
            rowKey={(row) => row.rowNumber}
            page={0}
            size={result.warnings.length || 1}
            totalElements={result.warnings.length}
            totalPages={1}
            onPageChange={() => undefined}
          />
        </section>
      )}
    </div>
  );
};
