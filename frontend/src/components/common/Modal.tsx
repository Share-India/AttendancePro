import { useEffect, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { cn } from '../../utils/cn';
import { Icon } from './Icon';

interface ModalProps {
  open: boolean;
  onClose: () => void;
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  size?: 'sm' | 'md' | 'lg';
}

const SIZES = {
  sm: 'max-w-md',
  md: 'max-w-2xl',
  lg: 'max-w-4xl',
};

export const Modal = ({ open, onClose, title, description, children, footer, size = 'md' }: ModalProps) => {
  const panelRef = useRef<HTMLDivElement>(null);

  // Callers almost never memoise onClose -- it is usually an arrow function
  // declared in the body of the page -- so it is a different function on every
  // render. Depending on it directly re-ran everything below on every
  // keystroke, and the focus() call then pulled focus out of whatever the user
  // was typing into: on a phone the keyboard closed after each letter, and on
  // a laptop the caret left the field. Held in a ref instead, so the effect
  // runs when the dialog opens and closes, and at no other time.
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    if (!open) return;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKeyDown);

    // Stop the page behind from scrolling while the dialog is open.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    // Move focus into the dialog so keyboard users are not left behind it.
    // Once, on opening -- never again, or it fights the user for the caret.
    panelRef.current?.focus();

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  if (!open) return null;

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-end justify-center p-0 sm:items-center sm:p-lg">
      <div
        className="absolute inset-0 bg-inverse-surface/50 animate-fade-in"
        onClick={onClose}
        aria-hidden="true"
      />

      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        tabIndex={-1}
        className={cn(
          // pad-safe-bottom: on phones this is a bottom sheet, so its
          // footer — where Confirm and Cancel live — otherwise ends up
          // under the iPhone home indicator.
          'pad-safe-bottom relative z-10 flex max-h-[92vh] w-full flex-col rounded-t-lg bg-surface-container-lowest shadow-overlay animate-fade-in sm:rounded-lg sm:pb-0',
          SIZES[size],
        )}
      >
        <header className="flex items-start justify-between gap-md border-b border-outline-variant/60 px-lg py-md">
          <div className="min-w-0">
            <h2 id="modal-title" className="text-headline-md text-on-surface">
              {title}
            </h2>
            {description && (
              <p className="mt-unit text-body-sm text-on-surface-variant">{description}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-full p-1.5 text-on-surface-variant transition-colors hover:bg-surface-container-low"
            aria-label="Close"
          >
            <Icon name="close" className="text-[22px]" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-lg py-lg">{children}</div>

        {footer && (
          <footer className="flex flex-wrap justify-end gap-sm border-t border-outline-variant/60 px-lg py-md">
            {footer}
          </footer>
        )}
      </div>
    </div>,
    document.body,
  );
};
