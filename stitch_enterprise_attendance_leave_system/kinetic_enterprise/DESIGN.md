---
name: Kinetic Enterprise
colors:
  surface: '#f7f9fb'
  surface-dim: '#d8dadc'
  surface-bright: '#f7f9fb'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f4f6'
  surface-container: '#eceef0'
  surface-container-high: '#e6e8ea'
  surface-container-highest: '#e0e3e5'
  on-surface: '#191c1e'
  on-surface-variant: '#444651'
  inverse-surface: '#2d3133'
  inverse-on-surface: '#eff1f3'
  outline: '#757682'
  outline-variant: '#c5c5d3'
  surface-tint: '#4059aa'
  primary: '#00236f'
  on-primary: '#ffffff'
  primary-container: '#1e3a8a'
  on-primary-container: '#90a8ff'
  inverse-primary: '#b6c4ff'
  secondary: '#505f76'
  on-secondary: '#ffffff'
  secondary-container: '#d0e1fb'
  on-secondary-container: '#54647a'
  tertiary: '#00311f'
  on-tertiary: '#ffffff'
  tertiary-container: '#004a31'
  on-tertiary-container: '#27c38a'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dce1ff'
  primary-fixed-dim: '#b6c4ff'
  on-primary-fixed: '#00164e'
  on-primary-fixed-variant: '#264191'
  secondary-fixed: '#d3e4fe'
  secondary-fixed-dim: '#b7c8e1'
  on-secondary-fixed: '#0b1c30'
  on-secondary-fixed-variant: '#38485d'
  tertiary-fixed: '#6ffbbe'
  tertiary-fixed-dim: '#4edea3'
  on-tertiary-fixed: '#002113'
  on-tertiary-fixed-variant: '#005236'
  background: '#f7f9fb'
  on-background: '#191c1e'
  surface-variant: '#e0e3e5'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
  headline-lg-mobile:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 32px
---

## Brand & Style

The design system is engineered for high-density enterprise environments where clarity, speed of task completion, and perceived reliability are paramount. The brand personality is **Trustworthy, Efficient, and Authoritative**, aimed at HR administrators and employees who require a stable, predictable interface for managing sensitive data.

The visual style is **Corporate / Modern**, leaning heavily into functional minimalism. It prioritizes information hierarchy through structured whitespace and a rigorous grid system rather than decorative elements. By utilizing a "Soft Professional" aesthetic, the system reduces the cognitive fatigue often associated with data-heavy HRMS platforms. Key characteristics include high-contrast typography, systematic depth, and a refined color application that guides the eye toward actionable insights.

## Colors

The palette is anchored by **Deep Corporate Blue**, used strategically for primary actions, navigation states, and brand presence to evoke authority. 

- **Primary (#1E3A8A):** Reserved for high-priority interactive elements like primary buttons and active sidebar states.
- **Secondary / Slate (#64748B):** Used for supporting text, icons, and secondary UI elements to maintain a calm, neutral environment.
- **Backgrounds:** A tiered system of **Slate-50 (#F8FAFC)** for page backgrounds and **White (#FFFFFF)** for content cards to create clear separation.
- **Semantic Colors:** Emerald Green (Success), Amber (Warning), and Rose (Danger) are used strictly for status indicators, data visualization, and feedback loops to ensure immediate user comprehension of system states.

## Typography

The design system utilizes **Inter** for its exceptional legibility in digital interfaces and its neutral, professional tone. 

- **Hierarchy:** Use bold weights (600-700) for page titles and section headers to provide strong anchoring. 
- **Body Text:** `body-md` (14px) is the standard for most interface text, including table cells and form labels, to maximize information density without sacrificing readability.
- **Labels:** Small, uppercase labels with increased letter spacing should be used for category headers and metadata to distinguish them from interactive content.
- **Contrast:** Always ensure a minimum contrast ratio of 4.5:1 for body text against backgrounds. Use Slate-900 for primary text and Slate-600 for secondary/supporting text.

## Layout & Spacing

This design system employs a **12-column fluid grid** for dashboard views and a **fixed-width centered container (1280px)** for settings and profile pages. 

- **Grid:** Use a 24px gutter between columns. 
- **Rhythm:** All spacing must be multiples of 4px. Use `md` (16px) for standard padding within cards and `lg` (24px) for spacing between major sections.
- **Density:** Provide a "Compact" mode for data tables which reduces row height and cell padding to 8px for power users managing large employee directories.
- **Mobile Adaptivity:** On mobile, the 12-column grid collapses to a 4-column layout with 16px side margins. Horizontal scrolling is permitted only for complex data tables.

## Elevation & Depth

Hierarchy is established through **Tonal Layers** and **Ambient Shadows**. 

1. **Level 0 (Base):** Slate-50 background. Used for the application canvas.
2. **Level 1 (Surface):** White background with a 1px Slate-200 border. Used for the primary content cards and data tables.
3. **Level 2 (Raised):** White background with a subtle, diffused shadow (0px 4px 6px -1px rgba(0, 0, 0, 0.1)). Used for hover states on cards and dropdown menus.
4. **Level 3 (Overlay):** White background with a prominent shadow (0px 10px 15px -3px rgba(0, 0, 0, 0.1)). Used for modals and slide-over panels.

Avoid heavy black shadows; use the primary color tinted at very low opacity (e.g., 2-5%) within shadows to maintain a clean, integrated corporate look.

## Shapes

The shape language is **Rounded**, strike a balance between modern friendliness and corporate structure.

- **Standard Elements:** Buttons, input fields, and small cards use a **0.5rem (8px)** radius.
- **Large Containers:** Main dashboard cards and modals use a **1rem (16px)** radius to soften the high-density layout.
- **Indicators:** Status badges and "chips" use a **pill-shaped** (full) radius to distinguish them clearly from interactive buttons.
- **Borders:** Use 1px solid borders for all container boundaries. Avoid 2px+ borders except for focus states on input fields.

## Components

### Buttons
- **Primary:** Solid Deep Corporate Blue with white text. 8px corner radius.
- **Secondary:** White background with 1px Slate-300 border and Slate-700 text.
- **Ghost:** No background or border, primary color text. Used for less emphasized actions.

### Input Fields
- Use a 1px Slate-300 border that transitions to a 2px Primary Blue border on focus. 
- Labels should always be visible above the field (no floating labels) in `body-sm` weight 600.

### Cards
- White background, 1px Slate-200 border, 16px corner radius.
- Use `lg` (24px) padding for dashboard widgets to prevent a cluttered feel.

### Data Tables
- Use alternating row stripes (Slate-50) for readability.
- Headers must be fixed on scroll, using `label-md` typography with a Slate-100 background.
- Row hover state: Subtle Slate-50 background change.

### Status Badges
- High-contrast, low-saturation backgrounds (e.g., Emerald-100) with dark text (Emerald-900) for maximum accessibility.
- Include a small leading dot icon for "Active," "Late," or "Pending" statuses to aid color-blind users.

### Sidebar
- Fixed-width (260px) with a dark theme variant (Slate-900) or clean light theme.
- Active states indicated by a 4px vertical primary blue bar on the left edge.