import js from "@eslint/js";
import eslintReact from "@eslint-react/eslint-plugin";
import prettier from "eslint-config-prettier/flat";
import astro from "eslint-plugin-astro";
import reactHooks from "eslint-plugin-react-hooks";
import { defineConfig, globalIgnores } from "eslint/config";
import globals from "globals";
import tseslint from "typescript-eslint";

/**
 * The lint rules of the websites: typescript-eslint's strict, type-checked rules everywhere,
 * React's for the documentation, Astro's for the landing page. Formatting is Prettier's
 * (prettier.config.ts), so no rule here is about layout.
 */
export default defineConfig(
  globalIgnores(["**/node_modules/", "**/dist/", "**/.astro/", "**/.next/", "docs/out/", "docs/next-env.d.ts"]),
  {
    linterOptions: {
      reportUnusedDisableDirectives: "error",
      reportUnusedInlineConfigs: "error",
    },
  },
  {
    files: ["**/*.{ts,tsx}"],
    extends: [js.configs.recommended, tseslint.configs.strictTypeChecked, tseslint.configs.stylisticTypeChecked],
    languageOptions: {
      parserOptions: {
        // Every file is checked with the tsconfig.json that includes it.
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      eqeqeq: ["error", "always", { null: "ignore" }],
      "@typescript-eslint/restrict-template-expressions": ["error", { allowNumber: true }],
      "@typescript-eslint/prefer-nullish-coalescing": ["error", { ignorePrimitives: { string: true } }],
      "@typescript-eslint/no-confusing-void-expression": ["error", { ignoreArrowShorthand: true }],
      "@typescript-eslint/consistent-type-imports": ["error", { fixStyle: "inline-type-imports" }],
      "@typescript-eslint/no-import-type-side-effects": "error",
      "@typescript-eslint/switch-exhaustiveness-check": ["error", { considerDefaultExhaustiveForUnions: true }],
      "no-console": ["error", { allow: ["warn", "error"] }],
      "object-shorthand": "error",
      "prefer-template": "error",
    },
  },
  {
    // React: the documentation.
    files: ["docs/{app,components,lib}/**/*.{ts,tsx}"],
    extends: [reactHooks.configs.flat["recommended-latest"], eslintReact.configs["strict-type-checked"]],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // React's own plugin checks hooks and what the React Compiler relies on; these are
      // @eslint-react's versions of the same rules.
      "@eslint-react/error-boundaries": "off",
      "@eslint-react/exhaustive-deps": "off",
      "@eslint-react/purity": "off",
      "@eslint-react/rules-of-hooks": "off",
      "@eslint-react/set-state-in-effect": "off",
      "@eslint-react/set-state-in-render": "off",
      "@eslint-react/static-components": "off",
      "@eslint-react/unsupported-syntax": "off",
      "@eslint-react/use-memo": "off",
    },
  },
  {
    // The landing page's pages and layouts.
    files: ["landing/**/*.astro"],
    extends: [astro.configs["flat/recommended"]],
  },
  {
    // Build tooling and command-line programs run on Node, and so do the documentation's pages
    // (they render at build time).
    files: ["*.config.ts", "*/*.config.ts", "landing/integrations/**/*.ts", "docs/{app,components,lib}/**/*.{ts,tsx}"],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      "no-console": "off",
    },
  },
  prettier,
  {
    // After Prettier's config, which turns it off: braces wherever a statement spans lines.
    rules: {
      curly: ["error", "multi-line"],
    },
  },
);
