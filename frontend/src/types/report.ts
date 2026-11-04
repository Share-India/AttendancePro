export type ColumnType = 'TEXT' | 'NUMBER' | 'DATE' | 'TIME' | 'DURATION' | 'STATUS';

export interface ReportColumn {
  key: string;
  label: string;
  type: ColumnType;
}

export interface ReportSummary {
  type: string;
  title: string;
  description: string;
  /** These reports describe one named person, so the picker insists on one. */
  requiresEmployee: boolean;
}

export interface ReportResult {
  type: string;
  title: string;
  description: string;
  fromDate: string;
  toDate: string;
  scope: 'ORG' | 'TEAM';

  columns: ReportColumn[];
  rows: Record<string, unknown>[];

  page: number;
  size: number;
  totalRows: number;
  totalPages: number;

  totals: Record<string, number>;
  generatedAt: string;
}

/**
 * Renders one cell according to its column type.
 *
 * <p>The same typing that lets the server export a duration as a number in a
 * spreadsheet lets the screen show it as "7h 45m" — one definition, two
 * renderings, no chance of the table and the download disagreeing.
 */
export const formatCell = (value: unknown, type: ColumnType): string => {
  if (value === null || value === undefined || value === '') {
    return '—';
  }

  switch (type) {
    case 'DURATION': {
      const minutes = Number(value);
      if (Number.isNaN(minutes)) return String(value);
      if (minutes <= 0) return '0m';
      const hours = Math.floor(minutes / 60);
      const rest = minutes % 60;
      return hours > 0 ? `${hours}h ${rest}m` : `${rest}m`;
    }
    case 'TIME':
      return new Date(String(value)).toLocaleTimeString([], {
        hour: '2-digit',
        minute: '2-digit',
      });
    case 'DATE':
      return new Date(String(value)).toLocaleDateString();
    case 'NUMBER':
      return String(value);
    default:
      return String(value);
  }
};

/** Numeric columns are right-aligned so the digits line up down the column. */
export const isNumeric = (type: ColumnType): boolean =>
  type === 'NUMBER' || type === 'DURATION';
