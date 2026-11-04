import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/** Merges class names so a later utility wins over an earlier conflicting one. */
export const cn = (...inputs: ClassValue[]): string => twMerge(clsx(inputs));
