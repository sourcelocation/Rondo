import { type BufferGeometry, Mesh, type Texture, TextureLoader } from "three";
import { GLTFLoader } from "three/addons/loaders/GLTFLoader.js";
import { mergeGeometries } from "three/addons/utils/BufferGeometryUtils.js";
import keys from "../../assets/flight/keyboard.png?url";
import url from "../../assets/flight/flight.glb?url";

/**
 * The flight's models, made in Blender (assets/flight/airplane.blend) and exported beside it to flight.glb by the
 * Flight collection's exporter: the airliner, the cabin, and the laptop, each an empty with its pieces under it,
 * merged here into one geometry in the empty's frame (the laptop's is its own: the cabin places it). Each vertex
 * carries, besides its normal, what the shaders draw details by: the surface's own coordinates (`surface`), which part
 * it is (`part`, the numbers of airliner.ts's, cabin.ts's and laptop.ts's PART), and, in the cabin and the laptop, how
 * light and how glossy it is (`finish`). The laptop's keys are a picture (keyboard.png, baked in the .blend's
 * Keyboard bake scene): their normals, and where the caps are (laptop.ts).
 */
export class Model {
  private constructor(
    readonly airliner: BufferGeometry,
    readonly cabin: BufferGeometry,
    readonly laptop: BufferGeometry,
    readonly keys: Texture,
  ) {}

  static async load(): Promise<Model> {
    const [{ scene }, picture] = await Promise.all([
      new GLTFLoader().loadAsync(url),
      new TextureLoader().loadAsync(keys),
    ]);
    scene.updateMatrixWorld(true);
    const geometry = (name: string) => {
      const root = scene.getObjectByName(name);
      if (root == null) throw new Error(`flight.glb has no ${name}`);
      const toRoot = root.matrixWorld.clone().invert();
      const pieces: BufferGeometry[] = [];
      root.traverse((node) => {
        if (!(node instanceof Mesh)) return;
        const piece = (node.geometry as BufferGeometry).clone();
        piece.applyMatrix4(toRoot.clone().multiply(node.matrixWorld));
        // glTF names the attributes of its own with an underscore.
        for (const key of ["surface", "part", "finish"]) {
          if (!piece.hasAttribute(`_${key}`)) continue;
          piece.setAttribute(key, piece.getAttribute(`_${key}`));
          piece.deleteAttribute(`_${key}`);
        }
        pieces.push(piece);
      });
      // Every piece of one empty has the same attributes, or they don't merge.
      const merged = mergeGeometries(pieces);
      for (const piece of pieces) piece.dispose();
      return merged;
    };
    return new Model(geometry("airliner"), geometry("cabin"), geometry("laptop"), picture);
  }
}
