/**
 * Normalise captured PowerShell output before asserting on it.
 *
 * `pwsh` renders a thrown exception with ANSI colour and wraps long messages at
 * the host's width, inserting a ` | ` continuation marker. The same refusal is
 * therefore one unbroken line on Windows PowerShell and several fragments on a
 * CI runner. Assertions should describe the message, not the terminal, so this
 * strips the escape sequences and rejoins wrapped lines.
 *
 * Only used by the behaviour tests: the script's own output is unchanged.
 */
export function normalisePowerShellOutput(text: string): string {
  return text
    // ANSI SGR / colour sequences produced by the PowerShell formatter.
    .replace(/\u001b\[[0-9;]*[A-Za-z]/g, '')
    // pwsh's line-continuation marker, inserted at the host's wrap width.
    .replace(/\s+\|\s+/g, ' ')
    // Remaining line breaks and indentation from the multi-line host rendering.
    .replace(/\s+/g, ' ')
    .trim();
}
