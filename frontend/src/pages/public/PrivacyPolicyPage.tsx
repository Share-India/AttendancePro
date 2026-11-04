import { Link } from 'react-router-dom';
import { AppLogo } from '../../components/common/AppLogo';
import { ROUTES } from '../../constants/routes';

/**
 * The privacy notice shown to employees.
 *
 * <p>Every claim below is written from the actual schema rather than a
 * template — the tables named are real, and nothing is described that the
 * application does not do. If the data model changes, this page has to change
 * with it: a privacy notice that has drifted from the system is worse than
 * none, because people have relied on it.
 *
 * <p><strong>Before this goes live, ORGANISATION below must be filled in and
 * the whole notice reviewed by someone qualified in Indian data protection
 * law.</strong> It is an accurate description of the software; that is not the
 * same thing as a legally sufficient notice for a particular employer.
 */
const ORGANISATION = {
  /** The employer operating this installation — the data fiduciary. */
  name: 'Share India',
  /** Where an employee sends a question or a request about their data. */
  contactEmail: 'privacy@shareindia.co.in',
  /** DPDP Act 2023 requires a named contact for grievances. */
  grievanceOfficer: 'To be appointed',
  grievanceEmail: 'grievance@shareindia.co.in',
  postalAddress: 'To be completed',
  /** Shown as the effective date of this version. */
  lastUpdated: '30 October 2026',
};

const Section = ({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: React.ReactNode;
}) => (
  <section id={id} className="scroll-mt-lg">
    <h2 className="text-headline-md text-on-surface">{title}</h2>
    <div className="mt-sm flex flex-col gap-sm text-body-lg text-on-surface-variant">
      {children}
    </div>
  </section>
);

const DataGroup = ({
  title,
  items,
  note,
}: {
  title: string;
  items: string[];
  note?: string;
}) => (
  <div className="card p-md">
    <p className="text-title-sm text-on-surface">{title}</p>
    <ul className="mt-xs flex flex-col gap-1 text-body-md text-on-surface-variant">
      {items.map((item) => (
        <li key={item} className="flex gap-sm">
          <span aria-hidden="true">·</span>
          <span className="min-w-0">{item}</span>
        </li>
      ))}
    </ul>
    {note ? <p className="mt-sm text-body-sm text-on-surface-variant">{note}</p> : null}
  </div>
);

export const PrivacyPolicyPage = () => (
  <div className="min-h-screen bg-background">
    <header className="border-b border-outline-variant/60 bg-surface-container-lowest">
      <div className="mx-auto flex max-w-[760px] items-center justify-between gap-md px-margin-mobile py-lg md:px-margin-desktop">
        <AppLogo size={28} labelClassName="text-title-md text-primary" />
        <Link to={ROUTES.login} className="btn-ghost">
          Back to sign in
        </Link>
      </div>
    </header>

    <main className="pad-safe-bottom pad-safe-x mx-auto max-w-[760px] px-margin-mobile py-xl md:px-margin-desktop">
      <h1 className="text-display-sm text-on-surface">Privacy Notice</h1>
      <p className="mt-sm text-body-lg text-on-surface-variant">
        How {ORGANISATION.name} handles your personal data in this attendance and leave system.
      </p>
      <p className="mt-xs text-body-sm text-on-surface-variant">
        Last updated {ORGANISATION.lastUpdated}
      </p>

      <div className="mt-xl flex flex-col gap-xl">
        <Section id="scope" title="Who this applies to">
          <p>
            This notice is for people employed by or working with {ORGANISATION.name} who have an
            account in this system. {ORGANISATION.name} decides why and how your data is used here,
            which makes it the data fiduciary under the Digital Personal Data Protection Act, 2023.
          </p>
          <p>
            It covers only this application. Other systems your employer runs have their own
            notices.
          </p>
        </Section>

        <Section id="what" title="What the system holds about you">
          <p>
            Everything below is data this application actually stores. It is grouped by why it
            exists rather than by where it is kept.
          </p>

          <div className="mt-sm flex flex-col gap-md">
            <DataGroup
              title="Who you are"
              items={[
                'Name, employee code, date of birth, gender and blood group',
                'Official and personal email addresses, mobile and alternate mobile',
                'Emergency contact name and number',
                'Profile photograph, if one has been uploaded',
              ]}
              note="Blood group and emergency contact exist so somebody can be reached if you are hurt at work."
            />

            <DataGroup
              title="Your employment"
              items={[
                'Department, designation, office and work mode',
                'Joining date, confirmation date and, if applicable, exit date',
                'Your reporting manager, and who reports to you',
                'The shift you are assigned to and the policies that apply to you',
              ]}
            />

            <DataGroup
              title="Your attendance"
              items={[
                'Punch-in and punch-out times, and each break you start and end',
                'Hours worked, late minutes, early-exit minutes and overtime',
                'Your location at the moment you punch, and how far that was from the office',
                'A flag, with reasons, where a punch looked unusual to the system',
              ]}
              note="See “About location” below — this is the part people most often want to understand."
            />

            <DataGroup
              title="What you ask for"
              items={[
                'Leave requests, including the dates and the reason you type',
                'Work-from-home and attendance-correction requests, and their reasons',
                'Who approved or rejected each one, when, and any comment they left',
                'Your leave balances',
              ]}
              note="Reasons are free text. Whatever you write is visible to your approvers and to HR."
            />

            <DataGroup
              title="Meetings and exams"
              items={[
                'Whether you opened a meeting join link, and when',
                'Exam answers, score, pass or fail, and time taken',
              ]}
              note="Exams here are for study and are not an input to appraisal. Opening a join link is recorded; the system cannot see inside the meeting itself."
            />

            <DataGroup
              title="Security and record-keeping"
              items={[
                'Sign-in attempts, successful and failed, with the IP address and browser used',
                'The IP address and browser recorded against punches and meeting joins',
                'An audit trail of changes made in the system — who changed what, and when',
                'Session tokens, stored only as irreversible hashes',
              ]}
            />
          </div>
        </Section>

        <Section id="location" title="About location">
          <p>
            Your location is captured <strong>only at the moment you punch in or out</strong>, to
            confirm you were at a permitted work location. The system does not track you between
            punches, does not run in the background, and has no continuous location feature.
          </p>
          <p>
            What is stored is the coordinates at that instant, the distance from the office, and a
            flag if that distance fell outside the permitted radius. Your device decides whether to
            provide location at all; if you refuse, that is handled by the attendance policy your
            employer has configured, not by this notice.
          </p>
        </Section>

        <Section id="why" title="Why it is held">
          <p>
            To record attendance and working hours; to operate leave, work-from-home and correction
            requests and their approvals; to produce payroll and statutory records your employer is
            required to keep; to run internal training exams; and to keep the system secure and its
            changes accountable.
          </p>
          <p>
            Under the DPDP Act this processing is for the purposes of your employment — including
            attendance, payroll, and safeguarding the employer against loss. It is not used for
            advertising, it is not sold, and it is not profiled for any purpose beyond what is
            described here.
          </p>
        </Section>

        <Section id="who" title="Who can see it">
          <p>Access depends on the role someone holds:</p>
          <div className="card overflow-hidden">
            <dl className="divide-y divide-outline-variant/40">
              {[
                ['You', 'Your own records, requests, results and notifications.'],
                [
                  'Your manager',
                  'Attendance, leave and requests for the people who report to them, so they can approve and plan.',
                ],
                [
                  'HR',
                  'Records across the organisation, to administer attendance, leave and payroll.',
                ],
                [
                  'Administrators',
                  'System configuration, roles and policies, plus the audit trail of changes.',
                ],
              ].map(([role, access]) => (
                <div key={role} className="flex flex-col gap-1 p-md sm:flex-row sm:gap-md">
                  <dt className="shrink-0 text-title-sm text-on-surface sm:w-40">{role}</dt>
                  <dd className="min-w-0 text-body-md text-on-surface-variant">{access}</dd>
                </div>
              ))}
            </dl>
          </div>
          <p>
            Attendance records can be corrected — by you through a request your manager approves, or
            by HR and administrators where a device failed or a correction was missed. Corrections
            are recorded internally, and a corrected day is marked as corrected on your own
            attendance screen.
          </p>
          <p>
            Your data is not shared outside {ORGANISATION.name} except where the law requires it, or
            with service providers who host or support this system and may only process it on your
            employer&rsquo;s instructions.
          </p>
        </Section>

        <Section id="keeping" title="How long it is kept">
          <p>
            Attendance, leave and payroll-related records are kept for as long as your employment
            continues and afterwards for the period your employer is required to retain employment
            records under Indian law.
          </p>
          <p>
            Be aware that this system does not currently delete anything automatically. Records
            remain until somebody removes them deliberately. If you want to know what is still held
            about you, or ask for something to be removed, use the contact below.
          </p>
        </Section>

        <Section id="rights" title="Your rights">
          <p>Under the Digital Personal Data Protection Act, 2023 you may:</p>
          <ul className="flex flex-col gap-sm">
            {[
              'Ask what personal data about you is being processed, and who it has been shared with.',
              'Ask for data that is wrong or incomplete to be corrected or completed.',
              'Ask for data to be erased, where it is no longer needed and the law does not require it to be kept.',
              'Nominate somebody to exercise these rights on your behalf if you die or become incapacitated.',
              'Raise a grievance, and escalate to the Data Protection Board of India if it is not resolved.',
            ].map((right) => (
              <li key={right} className="flex gap-sm">
                <span aria-hidden="true" className="text-primary">
                  —
                </span>
                <span className="min-w-0">{right}</span>
              </li>
            ))}
          </ul>
          <p>
            Some of these have limits in an employment context. A record your employer must keep by
            law, or an audit entry showing that a change was made, will not be deleted on request,
            because its purpose is precisely that it cannot be quietly removed.
          </p>
        </Section>

        <Section id="security" title="How it is protected">
          <p>
            Passwords are stored only as salted bcrypt hashes and never in a form anyone can read,
            including administrators. Password-reset links and session refresh tokens are likewise
            stored only as hashes. Accounts lock after repeated failed sign-ins.
          </p>
          <p>
            Access is controlled by role, and what you can reach is checked on the server on every
            request rather than hidden in the interface. Changes to records are written to an audit
            trail that the application cannot edit or delete.
          </p>
        </Section>

        <Section id="contact" title="Contact">
          <p>
            For any question about this notice, or to make a request about your data, write to{' '}
            <a
              href={`mailto:${ORGANISATION.contactEmail}`}
              className="text-primary underline underline-offset-2"
            >
              {ORGANISATION.contactEmail}
            </a>
            .
          </p>
          <p>
            Grievance officer: {ORGANISATION.grievanceOfficer} —{' '}
            <a
              href={`mailto:${ORGANISATION.grievanceEmail}`}
              className="text-primary underline underline-offset-2"
            >
              {ORGANISATION.grievanceEmail}
            </a>
            .
          </p>
          <p>{ORGANISATION.postalAddress}</p>
        </Section>

        <Section id="changes" title="Changes to this notice">
          <p>
            If what the system collects changes, this notice is updated with it and the date at the
            top changes. Where a change materially affects you, your employer will tell you rather
            than relying on you noticing.
          </p>
        </Section>
      </div>

      <footer className="mt-xl border-t border-outline-variant/60 pt-lg text-body-sm text-on-surface-variant">
        <Link to={ROUTES.login} className="text-primary underline underline-offset-2">
          Back to sign in
        </Link>
        <p className="mt-sm">
          © {new Date().getFullYear()} {ORGANISATION.name}
        </p>
      </footer>
    </main>
  </div>
);
