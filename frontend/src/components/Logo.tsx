/** The AgentGate mark: a ring open at one point, like a wax seal's break -- "reviewed," not a
 * generic checkmark badge. Plain inline SVG (no asset file, no icon-font dependency) so it
 * stays crisp at any size and works in the self-hosted/offline setting this console ships in. */
export function Logo({ size = 22 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" role="img" aria-label="AgentGate">
      <path
        d="M12 3.5a8.5 8.5 0 1 1-6.1 2.6"
        stroke="currentColor"
        strokeWidth="2.3"
        strokeLinecap="round"
      />
    </svg>
  );
}
