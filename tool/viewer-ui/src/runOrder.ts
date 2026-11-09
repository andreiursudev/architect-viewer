import type { Flow, FlowStep } from './api/types';

/**
 * The steps of a flow as a tree: the children of each step by its id, the roots under `null`. A step whose parent is not
 * part of the flow is a root. Siblings are ordered by start time, whatever order the backend listed them in.
 */
export function stepChildren(flow: Flow): Map<string | null, FlowStep[]> {
  const ids = new Set(flow.steps.map((s) => s.stepId));
  const children = new Map<string | null, FlowStep[]>();
  for (const step of flow.steps) {
    const parent = step.parentStepId != null && ids.has(step.parentStepId) ? step.parentStepId : null;
    const siblings = children.get(parent);
    if (siblings) siblings.push(step);
    else children.set(parent, [step]);
  }
  for (const siblings of children.values()) siblings.sort((a, b) => a.startEpochNanos - b.startEpochNanos);
  return children;
}

/**
 * The steps in the order the sequence diagram draws their requests: each step, then the steps it caused, depth first.
 * The board numbers its hops in this order, so both views tell the run the same way.
 */
export function stepsInRunOrder(flow: Flow): FlowStep[] {
  const children = stepChildren(flow);
  const ordered: FlowStep[] = [];
  const visited = new Set<FlowStep>(); // a corrupt recording must not loop
  const visit = (step: FlowStep) => {
    if (visited.has(step)) return;
    visited.add(step);
    ordered.push(step);
    for (const child of children.get(step.stepId) ?? []) visit(child);
  };
  for (const root of children.get(null) ?? []) visit(root);
  return ordered;
}
