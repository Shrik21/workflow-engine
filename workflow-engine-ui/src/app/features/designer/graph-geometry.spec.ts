import { WorkflowConnection, WorkflowNode } from '../../core/models/workflow.models';
import {
  MAX_ZOOM,
  MIN_ZOOM,
  NODE_HEIGHT,
  NODE_WIDTH,
  autoLayout,
  boundingBox,
  clampZoom,
  edgePath,
  fitViewport,
  hasCoordinates,
  inputPort,
  nodeRect,
  outputPort,
  outputPortsOf,
  snap,
  toWorld,
  zoomAt,
} from './graph-geometry';

function node(id: string, type = 'PLUGIN', x?: number, y?: number): WorkflowNode {
  return {
    id,
    type,
    name: id,
    presentation: x === undefined ? undefined : { x, y: y ?? 0 },
  };
}

function edge(source: string, target: string, sourcePort?: string): WorkflowConnection {
  return { source, target, sourcePort: sourcePort ?? null };
}

describe('graph geometry', () => {
  describe('nodeRect', () => {
    it('defaults a node with no coordinates to the origin instead of NaN', () => {
      expect(nodeRect(node('a'))).toEqual({ x: 0, y: 0, width: NODE_WIDTH, height: NODE_HEIGHT });
    });

    it('ignores non-numeric coordinates', () => {
      const broken = { ...node('a'), presentation: { x: 'left' as unknown as number, y: 10 } };
      expect(nodeRect(broken).x).toBe(0);
    });
  });

  describe('ports', () => {
    const rect = nodeRect(node('a', 'DECISION', 100, 200));

    it('places the input port on the middle of the left edge', () => {
      expect(inputPort(rect)).toEqual({ x: 100, y: 200 + NODE_HEIGHT / 2 });
    });

    it('centres a single output port', () => {
      expect(outputPort(rect, 0, 1)).toEqual({ x: 100 + NODE_WIDTH, y: 200 + NODE_HEIGHT / 2 });
    });

    it('spreads several branch ports down the right edge without overlapping', () => {
      const first = outputPort(rect, 0, 3);
      const second = outputPort(rect, 1, 3);
      const third = outputPort(rect, 2, 3);

      expect(first.x).toBe(100 + NODE_WIDTH);
      expect(second.y).toBeGreaterThan(first.y);
      expect(third.y).toBeGreaterThan(second.y);
      // All three stay inside the node's height, or the ports would float off the box.
      expect(first.y).toBeGreaterThanOrEqual(200);
      expect(third.y).toBeLessThanOrEqual(200 + NODE_HEIGHT);
    });
  });

  describe('edgePath', () => {
    it('produces a cubic bezier between the two points', () => {
      const path = edgePath({ x: 0, y: 0 }, { x: 200, y: 100 });
      expect(path.startsWith('M 0 0 C')).toBeTrue();
      expect(path.endsWith('200 100')).toBeTrue();
    });

    it('bows a backwards edge more widely, so a loop stays readable', () => {
      const forward = controlOffset(edgePath({ x: 0, y: 0 }, { x: 300, y: 0 }));
      const backward = controlOffset(edgePath({ x: 300, y: 0 }, { x: 0, y: 0 }));
      expect(backward).toBeGreaterThan(forward);
    });

    function controlOffset(path: string): number {
      // "M x y C c1x c1y, c2x c2y, x y" - the first control point's horizontal distance from the start.
      const numbers = path.match(/-?\d+(\.\d+)?/g)!.map(Number);
      return Math.abs(numbers[2] - numbers[0]);
    }
  });

  describe('viewport', () => {
    it('snaps to the grid', () => {
      expect(snap(103)).toBe(100);
      expect(snap(107)).toBe(110);
      expect(snap(-4)).toBe(-0);
    });

    it('clamps zoom to a usable range', () => {
      expect(clampZoom(0.01)).toBe(MIN_ZOOM);
      expect(clampZoom(99)).toBe(MAX_ZOOM);
      expect(clampZoom(1.5)).toBe(1.5);
    });

    it('converts screen coordinates to canvas coordinates', () => {
      const viewport = { pan: { x: 100, y: 50 }, zoom: 2 };
      expect(toWorld({ x: 300, y: 150 }, viewport)).toEqual({ x: 100, y: 50 });
    });

    it('keeps the point under the cursor fixed while zooming', () => {
      const before = { pan: { x: 0, y: 0 }, zoom: 1 };
      const cursor = { x: 400, y: 300 };
      const worldBefore = toWorld(cursor, before);

      const after = zoomAt(before, cursor, 1.5);
      const worldAfter = toWorld(cursor, after);

      expect(worldAfter.x).toBeCloseTo(worldBefore.x, 6);
      expect(worldAfter.y).toBeCloseTo(worldBefore.y, 6);
      expect(after.zoom).toBeCloseTo(1.5, 6);
    });

    it('does not let a zoom gesture escape the clamp', () => {
      const zoomed = zoomAt({ pan: { x: 0, y: 0 }, zoom: MAX_ZOOM }, { x: 0, y: 0 }, 4);
      expect(zoomed.zoom).toBe(MAX_ZOOM);
    });
  });

  describe('boundingBox and fitViewport', () => {
    it('returns null for an empty graph', () => {
      expect(boundingBox([])).toBeNull();
    });

    it('covers every node', () => {
      const box = boundingBox([node('a', 'START', 0, 0), node('b', 'END', 300, 100)])!;
      expect(box.x).toBe(0);
      expect(box.y).toBe(0);
      expect(box.width).toBe(300 + NODE_WIDTH);
      expect(box.height).toBe(100 + NODE_HEIGHT);
    });

    it('never magnifies a small graph beyond its natural size', () => {
      const viewport = fitViewport([node('a', 'START', 0, 0)], { width: 4000, height: 3000 });
      expect(viewport.zoom).toBe(1);
    });

    it('shrinks to fit a wide graph', () => {
      const viewport = fitViewport(
        [node('a', 'START', 0, 0), node('b', 'END', 4000, 0)],
        { width: 800, height: 600 },
      );
      expect(viewport.zoom).toBeLessThan(1);
      expect(viewport.zoom).toBeGreaterThanOrEqual(MIN_ZOOM);
    });

    it('falls back to a sane viewport with no nodes or no canvas', () => {
      expect(fitViewport([], { width: 800, height: 600 }).zoom).toBe(1);
      expect(fitViewport([node('a', 'START', 0, 0)], { width: 0, height: 0 }).zoom).toBe(1);
    });
  });

  describe('autoLayout', () => {
    it('places nodes in layers by distance from the start', () => {
      const nodes = [node('start', 'START'), node('middle'), node('end', 'END')];
      const connections = [edge('start', 'middle'), edge('middle', 'end')];

      const positions = autoLayout(nodes, connections);

      expect(positions.get('start')!.x).toBeLessThan(positions.get('middle')!.x);
      expect(positions.get('middle')!.x).toBeLessThan(positions.get('end')!.x);
    });

    it('separates siblings vertically so branches do not overlap', () => {
      const nodes = [node('start', 'START'), node('yes'), node('no')];
      const connections = [edge('start', 'yes', 'yes'), edge('start', 'no', 'no')];

      const positions = autoLayout(nodes, connections);

      expect(positions.get('yes')!.x).toBe(positions.get('no')!.x);
      expect(positions.get('yes')!.y).not.toBe(positions.get('no')!.y);
    });

    it('terminates on a cycle and still positions every node', () => {
      const nodes = [node('start', 'START'), node('a'), node('b')];
      const connections = [edge('start', 'a'), edge('a', 'b'), edge('b', 'a')];

      const positions = autoLayout(nodes, connections);

      expect(positions.size).toBe(3);
    });

    it('places an unreachable node, such as a compensation node, past the last layer', () => {
      const nodes = [node('start', 'START'), node('end', 'END'), node('compensate')];
      const connections = [edge('start', 'end')];

      const positions = autoLayout(nodes, connections);

      expect(positions.get('compensate')!.x).toBeGreaterThan(positions.get('end')!.x);
    });

    it('handles a graph with no start node at all', () => {
      const positions = autoLayout([node('a'), node('b')], [edge('a', 'b')]);
      expect(positions.size).toBe(2);
    });

    it('returns nothing for an empty graph', () => {
      expect(autoLayout([], []).size).toBe(0);
    });
  });

  describe('hasCoordinates', () => {
    it('is false when any node lacks coordinates, which is what triggers layout', () => {
      expect(hasCoordinates([node('a', 'START', 0, 0), node('b')])).toBeFalse();
      expect(hasCoordinates([node('a', 'START', 0, 0), node('b', 'END', 10, 10)])).toBeTrue();
      expect(hasCoordinates([])).toBeFalse();
    });
  });

  describe('outputPortsOf', () => {
    it('collects branches from conditions, the default branch and existing edges', () => {
      const decision: WorkflowNode = {
        ...node('d', 'DECISION'),
        conditions: [
          { branch: 'approved', expression: 'a' },
          { branch: 'rejected', expression: 'b' },
        ],
        defaultBranch: 'fallback',
      };
      const connections = [edge('d', 'x', 'legacy'), edge('d', 'y', 'approved')];

      expect(outputPortsOf(decision, connections)).toEqual([
        'approved',
        'rejected',
        'fallback',
        'legacy',
      ]);
    });

    it('is empty for a single-exit node', () => {
      expect(outputPortsOf(node('a'), [edge('a', 'b')])).toEqual([]);
    });
  });
});
