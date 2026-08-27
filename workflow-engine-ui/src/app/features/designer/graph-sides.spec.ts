import {
  NODE_HEIGHT,
  NODE_WIDTH,
  Rect,
  Side,
  autoLayout,
  edgePath,
  facingSides,
  portOn,
  sideNormal,
} from './graph-geometry';
import { WorkflowConnection, WorkflowNode } from '../../core/models/workflow.models';

/**
 * Four-sided connection geometry.
 *
 * <p>This is what lets one canvas serve a left-to-right and a top-to-bottom layout without a mode switch,
 * so the tests are mostly about the choice of face: which one an edge leaves, which it arrives at, and that
 * the curve actually sets off in that direction rather than always sideways.
 */
describe('graph geometry — four-sided connections', () => {
  function rect(x: number, y: number): Rect {
    return { x, y, width: NODE_WIDTH, height: NODE_HEIGHT };
  }

  const origin = rect(0, 0);

  // ------------------------------------------------------------------ port placement

  it('places a single port at the centre of its side', () => {
    expect(portOn(origin, 'left')).toEqual({ x: 0, y: NODE_HEIGHT / 2 });
    expect(portOn(origin, 'right')).toEqual({ x: NODE_WIDTH, y: NODE_HEIGHT / 2 });
    expect(portOn(origin, 'top')).toEqual({ x: NODE_WIDTH / 2, y: 0 });
    expect(portOn(origin, 'bottom')).toEqual({ x: NODE_WIDTH / 2, y: NODE_HEIGHT });
  });

  it('spreads several ports along the side they share', () => {
    const first = portOn(origin, 'right', 0, 3);
    const second = portOn(origin, 'right', 1, 3);
    const third = portOn(origin, 'right', 2, 3);

    expect(first.x).toBe(NODE_WIDTH);
    expect(first.y).toBeLessThan(second.y);
    expect(second.y).toBeLessThan(third.y);
    // Inset, so the outermost handle does not sit on the card's corner radius.
    expect(first.y).toBeGreaterThan(0);
    expect(third.y).toBeLessThan(NODE_HEIGHT);
  });

  it('spreads along the other axis on a horizontal side', () => {
    const first = portOn(origin, 'bottom', 0, 3);
    const third = portOn(origin, 'bottom', 2, 3);

    expect(first.y).toBe(NODE_HEIGHT);
    expect(third.y).toBe(NODE_HEIGHT);
    expect(first.x).toBeLessThan(third.x);
  });

  it('points each normal out of its own face', () => {
    expect(sideNormal('right')).toEqual({ x: 1, y: 0 });
    expect(sideNormal('left')).toEqual({ x: -1, y: 0 });
    expect(sideNormal('top')).toEqual({ x: 0, y: -1 });
    expect(sideNormal('bottom')).toEqual({ x: 0, y: 1 });
  });

  // ------------------------------------------------------------------ choosing faces

  it('joins a node to its right with right-to-left', () => {
    expect(facingSides(origin, rect(400, 0))).toEqual({ from: 'right', to: 'left' });
  });

  it('joins a node to its left with left-to-right', () => {
    // A loop back to an earlier node leaves the face it is actually heading for.
    expect(facingSides(origin, rect(-400, 0))).toEqual({ from: 'left', to: 'right' });
  });

  it('joins a node below with bottom-to-top', () => {
    expect(facingSides(origin, rect(0, 400))).toEqual({ from: 'bottom', to: 'top' });
  });

  it('joins a node above with top-to-bottom', () => {
    expect(facingSides(origin, rect(0, -400))).toEqual({ from: 'top', to: 'bottom' });
  });

  it('prefers horizontal when the two separations are close', () => {
    // A plain |dx| >= |dy| test flips the edge across the node the moment a drag crosses the exact
    // diagonal, which reads as flicker. The band either side of 45° resolves to left-to-right, which is
    // how a workflow is read when nothing suggests otherwise.
    expect(facingSides(origin, rect(300, 300)).from).toBe('right');
    expect(facingSides(origin, rect(300, 320)).from).toBe('right');
  });

  it('switches to vertical once the drop is clearly greater', () => {
    expect(facingSides(origin, rect(300, 600)).from).toBe('bottom');
  });

  it('picks something usable for overlapping nodes', () => {
    // Degenerate rather than impossible: two nodes can be dropped on the same spot.
    expect(facingSides(origin, rect(0, 0))).toEqual({ from: 'right', to: 'left' });
  });

  // ------------------------------------------------------------------ the curve

  it('leaves and arrives along the normals of the faces it touches', () => {
    const path = edgePath({ x: 0, y: 0 }, { x: 0, y: 200 }, 'bottom', 'top');
    // M x y C c1x c1y, c2x c2y, x y — the two leading numbers are the start point.
    const [, , c1x, c1y, c2x, c2y] = numbers(path);

    // Straight down out of the source and straight up into the target: no sideways excursion.
    expect(c1x).toBe(0);
    expect(c1y).toBeGreaterThan(0);
    expect(c2x).toBe(0);
    expect(c2y).toBeLessThan(200);
  });

  it('still produces the original left-to-right curve by default', () => {
    // The defaults are the pre-existing behaviour, so every caller that has not been taught about sides
    // keeps drawing exactly what it drew before.
    expect(edgePath({ x: 0, y: 0 }, { x: 200, y: 0 })).toBe(
      edgePath({ x: 0, y: 0 }, { x: 200, y: 0 }, 'right', 'left'),
    );
  });

  it('bows wider when the target sits behind the face the edge leaves', () => {
    const forward = edgePath({ x: 0, y: 0 }, { x: 300, y: 0 }, 'right', 'left');
    const backward = edgePath({ x: 0, y: 0 }, { x: -300, y: 0 }, 'right', 'left');

    // Without the extra bow a doubling-back edge folds over the node it just left. Index 2 is c1x, the
    // first control point's horizontal reach out of the source.
    const forwardReach = Math.abs(numbers(forward)[2]);
    const backwardReach = Math.abs(numbers(backward)[2]);
    expect(backwardReach).toBeGreaterThan(forwardReach);
  });

  it('degrades to a line when both ends coincide', () => {
    // A collapsed bezier gives the arrow marker no direction to orient by.
    expect(edgePath({ x: 10, y: 10 }, { x: 10, y: 10 }, 'right', 'left')).toContain('L');
  });

  // ------------------------------------------------------------------ layout direction

  function node(id: string, type = 'PLUGIN'): WorkflowNode {
    return { id, type } as WorkflowNode;
  }

  function connect(source: string, target: string): WorkflowConnection {
    return { source, target } as WorkflowConnection;
  }

  const chain = [node('a', 'START'), node('b'), node('c')];
  const links = [connect('a', 'b'), connect('b', 'c')];

  it('advances left to right by default', () => {
    const positions = autoLayout(chain, links);

    expect(positions.get('b')!.x).toBeGreaterThan(positions.get('a')!.x);
    expect(positions.get('b')!.y).toBe(positions.get('a')!.y);
  });

  it('advances top to bottom when asked', () => {
    const positions = autoLayout(chain, links, 'vertical');

    expect(positions.get('b')!.y).toBeGreaterThan(positions.get('a')!.y);
    expect(positions.get('b')!.x).toBe(positions.get('a')!.x);
  });

  it('a vertical layout produces vertically joined edges', () => {
    // The point of the whole change: layout and edges agree without either being told about the other.
    const positions = autoLayout(chain, links, 'vertical');
    const a = positions.get('a')!;
    const b = positions.get('b')!;

    expect(facingSides(rect(a.x, a.y), rect(b.x, b.y))).toEqual({ from: 'bottom', to: 'top' });
  });

  it('spreads siblings across the flow in both directions', () => {
    const branching = [node('a', 'START'), node('b'), node('c')];
    const fork = [connect('a', 'b'), connect('a', 'c')];

    const horizontal = autoLayout(branching, fork);
    expect(horizontal.get('b')!.x).toBe(horizontal.get('c')!.x);
    expect(horizontal.get('b')!.y).not.toBe(horizontal.get('c')!.y);

    const vertical = autoLayout(branching, fork, 'vertical');
    expect(vertical.get('b')!.y).toBe(vertical.get('c')!.y);
    expect(vertical.get('b')!.x).not.toBe(vertical.get('c')!.x);
  });
});

/** The numbers in an SVG path, in order: start x/y then the two control points and the end. */
function numbers(path: string): number[] {
  return (path.match(/-?\d+(\.\d+)?/g) ?? []).map(Number);
}
