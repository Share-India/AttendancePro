/**
 * Kinetic Enterprise design system.
 * Token values come from stitch_enterprise_attendance_leave_system/kinetic_enterprise/DESIGN.md
 * so the mockups and the app stay in step. Change a colour there, change it here.
 */
/** @type {import('tailwindcss').Config} */
export default {
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Surfaces
        surface: '#f7f9fb',
        'surface-dim': '#d8dadc',
        'surface-bright': '#f7f9fb',
        'surface-container-lowest': '#ffffff',
        'surface-container-low': '#f2f4f6',
        'surface-container': '#eceef0',
        'surface-container-high': '#e6e8ea',
        'surface-container-highest': '#e0e3e5',
        'surface-variant': '#e0e3e5',
        background: '#f7f9fb',

        // Content
        'on-surface': '#191c1e',
        'on-surface-variant': '#444651',
        'on-background': '#191c1e',
        'inverse-surface': '#2d3133',
        'inverse-on-surface': '#eff1f3',
        outline: '#757682',
        'outline-variant': '#c5c5d3',

        // Primary — deep corporate blue
        primary: '#00236f',
        'on-primary': '#ffffff',
        'primary-container': '#1e3a8a',
        'on-primary-container': '#90a8ff',
        'inverse-primary': '#b6c4ff',
        'surface-tint': '#4059aa',
        'primary-fixed': '#dce1ff',
        'primary-fixed-dim': '#b6c4ff',
        'on-primary-fixed': '#00164e',
        'on-primary-fixed-variant': '#264191',

        // Secondary — slate
        secondary: '#505f76',
        'on-secondary': '#ffffff',
        'secondary-container': '#d0e1fb',
        'on-secondary-container': '#54647a',
        'secondary-fixed': '#d3e4fe',
        'secondary-fixed-dim': '#b7c8e1',
        'on-secondary-fixed': '#0b1c30',
        'on-secondary-fixed-variant': '#38485d',

        // Tertiary — emerald, used for present/approved states
        tertiary: '#00311f',
        'on-tertiary': '#ffffff',
        'tertiary-container': '#004a31',
        'on-tertiary-container': '#27c38a',
        'tertiary-fixed': '#6ffbbe',
        'tertiary-fixed-dim': '#4edea3',
        'on-tertiary-fixed': '#002113',
        'on-tertiary-fixed-variant': '#005236',

        // Semantic — status only, never decoration
        error: '#ba1a1a',
        'on-error': '#ffffff',
        'error-container': '#ffdad6',
        'on-error-container': '#93000a',
        warning: '#a15c00',
        'warning-container': '#ffddb0',
        'on-warning-container': '#5c3a00',
        success: '#005236',
        'success-container': '#a6f2d0',
        'on-success-container': '#00311f',
      },

      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', 'Segoe UI', 'Helvetica', 'Arial', 'sans-serif'],
        // Durations and clocks need columns that line up.
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'Consolas', 'monospace'],
      },

      fontSize: {
        'display-lg': ['36px', { lineHeight: '44px', letterSpacing: '-0.02em', fontWeight: '700' }],
        'headline-lg': ['28px', { lineHeight: '36px', letterSpacing: '-0.01em', fontWeight: '600' }],
        'headline-lg-mobile': ['24px', { lineHeight: '32px', fontWeight: '600' }],
        'headline-md': ['20px', { lineHeight: '28px', fontWeight: '600' }],
        'body-lg': ['16px', { lineHeight: '24px', fontWeight: '400' }],
        'body-md': ['14px', { lineHeight: '20px', fontWeight: '400' }],
        'body-sm': ['12px', { lineHeight: '16px', fontWeight: '400' }],
        'label-md': ['12px', { lineHeight: '16px', letterSpacing: '0.05em', fontWeight: '600' }],
      },

      spacing: {
        unit: '4px',
        xs: '4px',
        sm: '8px',
        md: '16px',
        lg: '24px',
        xl: '32px',
        gutter: '24px',
        'margin-mobile': '16px',
        'margin-desktop': '32px',
      },

      borderRadius: {
        sm: '0.25rem',
        DEFAULT: '0.5rem',
        md: '0.75rem',
        lg: '1rem',
        xl: '1.5rem',
        full: '9999px',
      },

      boxShadow: {
        // Level 2 — raised: hover states, dropdowns
        raised: '0px 4px 6px -1px rgba(0, 35, 111, 0.10), 0px 2px 4px -2px rgba(0, 35, 111, 0.06)',
        // Level 3 — overlay: modals, slide-overs
        overlay: '0px 10px 15px -3px rgba(0, 35, 111, 0.10), 0px 4px 6px -4px rgba(0, 35, 111, 0.05)',
        panel: '-10px 0 15px -3px rgba(0, 0, 0, 0.05)',
      },

      keyframes: {
        'fade-in': {
          from: { opacity: '0', transform: 'translateY(4px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
      },
      animation: {
        'fade-in': 'fade-in 180ms ease-out',
      },
    },
  },
  plugins: [],
};
