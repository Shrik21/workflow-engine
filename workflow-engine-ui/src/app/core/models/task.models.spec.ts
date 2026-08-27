import { TaskSummary, needsAttention, priorityLabel } from './task.models';

function task(overrides: Partial<TaskSummary> = {}): TaskSummary {
  return {
    taskId: 't1',
    executionId: 'e1',
    workflowId: 'w1',
    workflowName: 'Salary approval',
    workflowVersion: 1,
    nodeId: 'approve',
    taskName: 'Approve salary change',
    description: null,
    status: 'OPEN',
    priority: 'NORMAL',
    assigneeUsername: null,
    assignedToMe: false,
    claimable: true,
    candidateGroups: 1,
    formDefinitionId: 'f1',
    formVersion: 2,
    createdAt: new Date().toISOString(),
    dueAt: null,
    expiresAt: null,
    overdue: false,
    hasDraft: false,
    completedAt: null,
    completedBy: null,
    // Required on TaskSummary and previously missing here. Specs are not compiled by `ng build`, so this
    // stayed broken silently after the field was added.
    external: false,
    ...overrides,
  };
}

describe('task models', () => {
  it('labels each priority', () => {
    expect(priorityLabel('LOW')).toBe('Low');
    expect(priorityLabel('NORMAL')).toBe('Normal');
    expect(priorityLabel('HIGH')).toBe('High');
    expect(priorityLabel('URGENT')).toBe('Urgent');
  });

  it('flags overdue and urgent tasks, and nothing else', () => {
    expect(needsAttention(task())).toBeFalse();
    expect(needsAttention(task({ overdue: true }))).toBeTrue();
    expect(needsAttention(task({ priority: 'URGENT' }))).toBeTrue();
    expect(needsAttention(task({ priority: 'HIGH' }))).toBeFalse();
  });

  it('takes the server\'s word for overdue rather than recomputing it', () => {
    // A due date in the past with overdue:false. The server measured against its own clock, and it is the one
    // that decided whether a reminder went out; disagreeing here would show a badge nobody was told about.
    const stale = task({ dueAt: new Date(Date.now() - 60_000).toISOString(), overdue: false });

    expect(needsAttention(stale)).toBeFalse();
  });
});
