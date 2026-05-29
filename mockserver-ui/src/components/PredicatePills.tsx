import Box from '@mui/material/Box';
import Chip from '@mui/material/Chip';
import type { ConversationPredicates } from '../lib/llmTraffic';

interface PredicatePillsProps {
  predicates: ConversationPredicates;
}

interface PillDef {
  key: string;
  label: string;
}

// Cap user-supplied values rendered in pill labels so a long regex source or
// substring (whether accidental or deliberately crafted) cannot break the
// dashboard layout. Chip labels are React nodes (not HTML), so this is a
// length-only concern — not an XSS one.
const MAX_PILL_VALUE_LENGTH = 60;

function truncate(value: string): string {
  if (value.length <= MAX_PILL_VALUE_LENGTH) return value;
  return value.substring(0, MAX_PILL_VALUE_LENGTH) + '…';
}

function buildPills(predicates: ConversationPredicates): PillDef[] {
  const pills: PillDef[] = [];

  // The turnIndex predicate is intentionally NOT rendered as a pill here —
  // the parent JsonListItem already shows a `turn N of M` ordering chip
  // derived from scenario state, which is more useful (it's always present
  // even when the turn matches via other predicates) and would be a
  // duplicate with this pill.
  if (predicates.latestMessageContains != null) {
    pills.push({
      key: 'latestMessageContains',
      label: `latest msg ⊃ "${truncate(predicates.latestMessageContains)}"`,
    });
  }
  if (predicates.latestMessageMatches != null) {
    pills.push({
      key: 'latestMessageMatches',
      label: `latest msg ~ /${truncate(predicates.latestMessageMatches)}/`,
    });
  }
  if (predicates.latestMessageRole != null) {
    pills.push({
      key: 'latestMessageRole',
      label: `latest role = ${predicates.latestMessageRole}`,
    });
  }
  if (predicates.containsToolResultFor != null) {
    pills.push({
      key: 'containsToolResultFor',
      label: `has tool_result for ${truncate(predicates.containsToolResultFor)}`,
    });
  }
  if (predicates.normalization != null) {
    const n = predicates.normalization;
    const parts: string[] = [];
    if (n.collapseWhitespace) parts.push('ws');
    if (n.lowercase) parts.push('lower');
    if (n.sortJsonKeys) parts.push('json-keys');
    if (n.dropBuiltInVolatileFields) parts.push('volatile');
    if (n.dropVolatileFields && n.dropVolatileFields.length > 0) parts.push(`drop ${n.dropVolatileFields.length}`);
    pills.push({
      key: 'normalization',
      label: parts.length > 0 ? `normalised: ${parts.join(', ')}` : 'normalised',
    });
  }

  return pills;
}

export default function PredicatePills({ predicates }: PredicatePillsProps) {
  const pills = buildPills(predicates);
  if (pills.length === 0) return null;

  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5 }}>
      {pills.map((pill) => (
        <Chip
          key={pill.key}
          label={pill.label}
          size="small"
          color="info"
          variant="outlined"
          sx={{ height: 22, fontSize: '0.7rem' }}
        />
      ))}
    </Box>
  );
}
