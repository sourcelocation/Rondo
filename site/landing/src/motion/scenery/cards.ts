import { gsap } from "gsap";
import {
  CanvasTexture,
  DoubleSide,
  DynamicDrawUsage,
  InstancedBufferAttribute,
  InstancedBufferGeometry,
  LinearFilter,
  LinearMipmapLinearFilter,
  Mesh,
  PlaneGeometry,
  ShaderMaterial,
  Vector2,
  type WebGLRenderer,
} from "three";
import type { Flashcard } from "../../content/cards";

/** A shadow a card casts, as its CSS box-shadow has it: offset down, blur and spread (CSS pixels), and alpha. */
export interface Shadow {
  readonly y: number;
  readonly blur: number;
  readonly spread: number;
  readonly alpha: number;
}

/**
 * How a set of cards is painted, as their CSS had it. The fill is a linear-gradient along `angle` (CSS degrees), from
 * white at `from`'s alpha to `tint` at `to`'s, starting `stop` of the way along; a hairline of ink at `edge`'s alpha
 * runs round it, and it casts its shadows in rgb(30 40 80).
 */
export interface Look {
  readonly fill: {
    readonly angle: number;
    readonly from: number;
    readonly to: number;
    readonly stop: number;
    readonly tint: readonly [number, number, number];
  };
  readonly edge: number;
  readonly shadows: readonly [Shadow] | readonly [Shadow, Shadow];
}

/**
 * A face to draw: a card's words, in the type of `sizer`, a card the page lays out but doesn't show. The sizer marks
 * which of the card's words its face has, each with `data-part` naming it.
 */
export interface Face {
  readonly card: Flashcard;
  readonly sizer: HTMLElement;
}

/**
 * Where a card is drawn in a frame: its centre on the screen (CSS pixels), its size against a card's, how far it's
 * turned about its upright and in its plane (radians), its blur (CSS pixels, as its own size has them), its opacity,
 * and its face.
 */
export interface Placement {
  x: number;
  y: number;
  scale: number;
  turn: number;
  roll: number;
  blur: number;
  opacity: number;
  face: number;
  /** How far it's darkened, from 0 to 1, standing back in a deck. */
  shade?: number | undefined;
  /** How much of its shadows it casts, from 0 to 1 (all of them unless said): none, pressed onto cards behind it. */
  lift?: number | undefined;
  /** How far down from its middle it's drawn, in its own CSS pixels: the rest is hidden behind a card in front. */
  cut?: number | undefined;
}

/** A cut that leaves all of a card. */
const WHOLE = 1e6;

/** Room round a card's face in its picture, for words wider than the card, across and down, in the card's widths. */
const OVERFLOW = [0.14, 0.04] as const;
/** The most of the picture's pixels a card's width is drawn across: words this size need no more. */
const SHARPEST = 640;
/** The largest picture the faces are drawn into, each way: one every GPU takes. */
const LARGEST = 4096;

const vertex = /* glsl */ `
  attribute vec2 aCentre;
  attribute float aScale;
  attribute float aTurn;
  attribute float aRoll;
  attribute float aBlur;
  attribute float aOpacity;
  attribute float aFace;
  attribute float aShade;
  attribute float aLift;
  attribute float aCut;
  uniform vec2 uViewport;
  uniform vec2 uSize;
  uniform float uMargin;
  uniform float uPerspective;
  varying vec2 vLocal;
  varying float vBlur;
  varying float vOpacity;
  varying float vFace;
  varying float vShade;
  varying float vLift;

  void main() {
    // The card's own CSS pixels from its middle, y down, its shadows and blur round it; then as CSS transforms it:
    // turned in its plane (rotate), turned about its upright and seen in perspective (perspective() rotateY()),
    // scaled, and moved to its centre. The perspective is left to the GPU's divide, so a turned face stays true. A card
    // casting no shadow needs no room for one, and one hidden below its cut isn't drawn there.
    float margin = aLift > 0.0 ? uMargin : 2.0;
    vec2 local = vec2(position.x, -position.y) * (uSize * 0.5 + margin + aBlur * 2.0);
    local.y = min(local.y, aCut);
    float cr = cos(aRoll);
    float sr = sin(aRoll);
    vec2 rolled = vec2(local.x * cr - local.y * sr, local.x * sr + local.y * cr);
    float ct = cos(aTurn);
    float st = sin(aTurn);
    vec3 turned = vec3(rolled.x * ct, rolled.y, -rolled.x * st);
    float w = uPerspective > 0.0 ? 1.0 - turned.z / uPerspective : 1.0;
    vec2 centre = vec2(aCentre.x / uViewport.x * 2.0 - 1.0, 1.0 - aCentre.y / uViewport.y * 2.0);
    vec2 offset = vec2(turned.x, -turned.y) * aScale * 2.0 / uViewport;
    gl_Position = vec4(centre * w + offset, 0.0, w);
    vLocal = local;
    vBlur = aBlur;
    vOpacity = aOpacity;
    vFace = aFace;
    vShade = aShade;
    vLift = aLift;
  }
`;

const fragment = /* glsl */ `
  uniform sampler2D uFaces;
  uniform vec2 uAtlas;
  uniform vec2 uCell;
  uniform vec2 uFace;
  uniform float uColumns;
  uniform float uUnit;
  uniform vec2 uSize;
  uniform float uRadius;
  uniform vec2 uFillDirection;
  uniform vec3 uFill;
  uniform vec3 uTint;
  uniform float uEdge;
  uniform vec4 uShadowA;
  uniform vec4 uShadowB;
  varying vec2 vLocal;
  varying float vBlur;
  varying float vOpacity;
  varying float vFace;
  varying float vShade;
  varying float vLift;

  const vec3 INK = vec3(0.043, 0.043, 0.051);
  const vec3 SHADE = vec3(0.118, 0.157, 0.314);
  // How dark the line is where a card pressed onto another meets it, and how wide, in pixels.
  const float CONTACT = 0.26;
  const float SEAM = 0.8;

  // How far p is outside a rounded box (negative inside).
  float box(vec2 p, vec2 halfSize, float radius) {
    vec2 q = abs(p) - halfSize + radius;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
  }

  float erf(float x) {
    float a = abs(x);
    float t = 1.0 + (0.278393 + (0.230389 + (0.000972 + 0.078108 * a) * a) * a) * a;
    t *= t;
    return sign(x) * (1.0 - 1.0 / (t * t));
  }

  // How much of a pixel an edge at distance d covers, the edge blurred as a Gaussian of sigma, as CSS blurs.
  float coverage(float d, float sigma) {
    return 0.5 - 0.5 * erf(d / (max(sigma, 0.0001) * 1.41421));
  }

  // A box-shadow (offset down, blur, spread, alpha): CSS's shadow blur is twice its Gaussian's sigma.
  float shadow(vec4 s, float sigma) {
    float d = box(vLocal - vec2(0.0, s.x), uSize * 0.5 + s.z, max(uRadius + s.z, 0.0));
    return s.w * coverage(d, sqrt(s.y * s.y * 0.25 + sigma * sigma));
  }

  vec4 over(vec4 top, vec4 bottom) {
    return top + bottom * (1.0 - top.a);
  }

  void main() {
    // The words' place in the faces' picture, and how much the screen shrinks them there.
    float face = floor(vFace + 0.5);
    vec2 cell = vec2(mod(face, uColumns), floor(face / uColumns));
    vec2 texel = (cell + 0.5) * uCell + vLocal / uSize * uFace;
    float shrunk = log2(max(length(dFdx(texel)), length(dFdy(texel))));

    float sigma = max(0.6 * length(fwidth(vLocal)), vBlur);
    float d = box(vLocal, uSize * 0.5, uRadius);
    float inside = coverage(d, sigma);

    // Under the card: its shadows, and the hairline round it. Pressed onto the card behind, its shadows give way to a
    // thin dark seam along its edge, so each card of a deck shows apart from the next.
    float shade = (shadow(uShadowA, vBlur) + shadow(uShadowB, vBlur)) * (1.0 - inside) * vLift;
    float edge = uEdge * max(coverage(d - 1.0, sigma) - inside, 0.0);
    // The seams are a deck's card widths apart, so they're smoothed less than edges are, or they'd run together.
    float crisp = max(0.35 * length(fwidth(vLocal)), vBlur);
    edge += CONTACT * (1.0 - vLift) * max(coverage(d - SEAM, crisp) - coverage(d, crisp), 0.0);
    vec4 colour = vec4(SHADE, 1.0) * shade;
    colour = over(vec4(INK, 1.0) * edge, colour);

    // Its fill, along its gradient, and a bright hairline just inside its top edge.
    vec2 halfSize = uSize * 0.5;
    float reach = abs(halfSize.x * uFillDirection.x) + abs(halfSize.y * uFillDirection.y);
    float along = clamp(dot(vLocal, uFillDirection) / (2.0 * reach) + 0.5, 0.0, 1.0);
    float t = clamp((along - uFill.z) / max(1.0 - uFill.z, 0.001), 0.0, 1.0);
    vec3 fill = mix(vec3(1.0), uTint, t);
    fill = mix(fill, vec3(1.0), 0.9 * (1.0 - smoothstep(0.0, 1.2, vLocal.y + halfSize.y)));
    colour = over(vec4(fill, 1.0) * mix(uFill.x, uFill.y, t) * inside, colour);

    // Its words: sharp, or as blurred as the card is, read from a copy of the picture about that soft, four times
    // half a pixel of it apart, which smooths that copy's coarse pixels over; kept to the card's own cell.
    vec2 low = cell * uCell + 1.0;
    vec2 high = (cell + 1.0) * uCell - 1.0;
    if (all(greaterThan(texel, low)) && all(lessThan(texel, high))) {
      float spread = vBlur * uUnit;
      if (spread < 0.5) {
        colour = over(textureLod(uFaces, texel / uAtlas, shrunk), colour);
      } else {
        float lod = max(shrunk, log2(spread * 1.6));
        vec2 step = vec2(0.5, 0.25) * exp2(lod);
        vec4 words = textureLod(uFaces, clamp(texel + step, low, high) / uAtlas, lod);
        words += textureLod(uFaces, clamp(texel - step, low, high) / uAtlas, lod);
        words += textureLod(uFaces, clamp(texel + vec2(-step.y, step.x), low, high) / uAtlas, lod);
        words += textureLod(uFaces, clamp(texel + vec2(step.y, -step.x), low, high) / uAtlas, lod);
        colour = over(words * 0.25, colour);
      }
    }
    // Darkened, toward the shadows' blue, as far back as it stands.
    colour.rgb = mix(colour.rgb, SHADE * colour.a, vShade);
    gl_FragColor = colour * vOpacity;
  }
`;

/** How a part of a card's face is set, as the page's CSS lays out a sizer: in CSS pixels from the card's corner. */
interface Part {
  /** Which of the card's words it is. */
  readonly words: "deck" | "front" | "back";
  readonly font: string;
  readonly colour: string;
  readonly spacing: string;
  /** Where its words start (`left`) or are centred (`center`), across. */
  readonly x: number;
  readonly align: CanvasTextAlign;
  /** Its middle, down. */
  readonly y: number;
}

/**
 * A set of cards drawn by the GPU in a single draw, as many as are showing, each placed and turned as CSS would: a quad
 * a card, its rounded edge, fill, hairline and shadows worked out where it's drawn, its words read from a picture of
 * every face, drawn in the page's type. A card out of focus is blurred there too: its edges and shadows softened, its
 * words read from a smaller copy of the picture.
 */
export class Cards {
  readonly mesh: Mesh;
  /** A card's size, in CSS pixels. */
  readonly size = new Vector2(1, 1);
  private readonly geometry = new InstancedBufferGeometry();
  private readonly attributes: {
    readonly centre: InstancedBufferAttribute;
    readonly scale: InstancedBufferAttribute;
    readonly turn: InstancedBufferAttribute;
    readonly roll: InstancedBufferAttribute;
    readonly blur: InstancedBufferAttribute;
    readonly opacity: InstancedBufferAttribute;
    readonly face: InstancedBufferAttribute;
    readonly shade: InstancedBufferAttribute;
    readonly lift: InstancedBufferAttribute;
    readonly cut: InstancedBufferAttribute;
  };
  private readonly uniforms = {
    uViewport: { value: new Vector2(1, 1) },
    uSize: { value: this.size },
    uRadius: { value: 0 },
    uFaces: { value: null as CanvasTexture | null },
    uAtlas: { value: new Vector2(1, 1) },
    uCell: { value: new Vector2(1, 1) },
    uFace: { value: new Vector2(1, 1) },
    uColumns: { value: 1 },
    uUnit: { value: 1 },
  };
  private texture: CanvasTexture | null = null;
  private faces: readonly Face[] = [];
  private readonly redraw = gsap.delayedCall(0.2, () => void this.draw()).pause();
  private count = 0;

  /**
   * Room for `capacity` cards painted as `look`, drawn by `renderer`; `perspective` (CSS pixels) for cards turned in
   * depth, 0 for flat ones.
   */
  constructor(
    private readonly renderer: WebGLRenderer,
    capacity: number,
    look: Look,
    perspective: number,
  ) {
    const plane = new PlaneGeometry(2, 2);
    this.geometry.index = plane.index;
    this.geometry.setAttribute("position", plane.getAttribute("position"));
    const attribute = (size: number) =>
      new InstancedBufferAttribute(new Float32Array(capacity * size), size).setUsage(DynamicDrawUsage);
    this.attributes = {
      centre: attribute(2),
      scale: attribute(1),
      turn: attribute(1),
      roll: attribute(1),
      blur: attribute(1),
      opacity: attribute(1),
      face: attribute(1),
      shade: attribute(1),
      lift: attribute(1),
      cut: attribute(1),
    };
    const { centre, scale, turn, roll, blur, opacity, face, shade, lift, cut } = this.attributes;
    this.geometry.setAttribute("aCentre", centre);
    this.geometry.setAttribute("aScale", scale);
    this.geometry.setAttribute("aTurn", turn);
    this.geometry.setAttribute("aRoll", roll);
    this.geometry.setAttribute("aBlur", blur);
    this.geometry.setAttribute("aOpacity", opacity);
    this.geometry.setAttribute("aFace", face);
    this.geometry.setAttribute("aShade", shade);
    this.geometry.setAttribute("aLift", lift);
    this.geometry.setAttribute("aCut", cut);
    this.geometry.instanceCount = 0;

    const angle = (look.fill.angle * Math.PI) / 180;
    const [first, second = { y: 0, blur: 0, spread: 0, alpha: 0 }] = look.shadows;
    const reach = Math.max(...look.shadows.map((shadow) => shadow.y + shadow.blur + Math.max(shadow.spread, 0)));
    const material = new ShaderMaterial({
      vertexShader: vertex,
      fragmentShader: fragment,
      uniforms: {
        ...this.uniforms,
        uMargin: { value: reach + 2 },
        uPerspective: { value: perspective },
        uFillDirection: { value: new Vector2(Math.sin(angle), -Math.cos(angle)) },
        uFill: { value: [look.fill.from, look.fill.to, look.fill.stop] },
        uTint: { value: look.fill.tint },
        uEdge: { value: look.edge },
        uShadowA: { value: [first.y, first.blur, first.spread, first.alpha] },
        uShadowB: { value: [second.y, second.blur, second.spread, second.alpha] },
      },
      transparent: true,
      premultipliedAlpha: true,
      depthTest: false,
      depthWrite: false,
      side: DoubleSide,
    });
    this.mesh = new Mesh(this.geometry, material);
    this.mesh.frustumCulled = false;
  }

  /**
   * Sizes the cards as the page lays out their sizers, and draws `faces` (all the first sizer's size): at once the
   * first time, resolving once they're drawn, and otherwise once the page has kept its size a moment, the old faces
   * stretched meanwhile.
   */
  async measure(faces: readonly Face[]): Promise<void> {
    const sizer = faces[0]?.sizer;
    if (sizer == null) return;
    const width = Math.max(sizer.offsetWidth, 1);
    const height = Math.max(sizer.offsetHeight, 1);
    if (this.texture != null && width === this.size.x && height === this.size.y) return;
    this.size.set(width, height);
    this.uniforms.uRadius.value = Number.parseFloat(getComputedStyle(sizer).borderTopLeftRadius) || 0;
    this.faces = faces;
    if (this.texture == null) await this.draw();
    else this.redraw.restart(true);
  }

  /** Has the next draw take one card, unseen, even with none showing: for the GPU to ready what drawing them takes. */
  warm(): void {
    if (this.geometry.instanceCount > 0) return;
    this.begin();
    this.add({ x: 0, y: 0, scale: 1, turn: 0, roll: 0, blur: 0, opacity: 1, face: 0 });
    this.attributes.opacity.setX(0, 0);
    this.end();
  }

  setViewport(width: number, height: number): void {
    this.uniforms.uViewport.value.set(width, height);
  }

  /** Starts a frame's cards, drawn in the order they're added, the first lowest. */
  begin(): void {
    this.count = 0;
  }

  /** A card, drawn over those added before it. */
  add(card: Readonly<Placement>): void {
    if (card.opacity <= 0.001) return;
    const index = this.count++;
    const { attributes } = this;
    attributes.centre.setXY(index, card.x, card.y);
    attributes.scale.setX(index, card.scale);
    attributes.turn.setX(index, card.turn);
    attributes.roll.setX(index, card.roll);
    attributes.blur.setX(index, card.blur);
    attributes.opacity.setX(index, card.opacity);
    attributes.face.setX(index, card.face);
    attributes.shade.setX(index, card.shade ?? 0);
    attributes.lift.setX(index, card.lift ?? 1);
    attributes.cut.setX(index, card.cut ?? WHOLE);
  }

  /**
   * How far down from its middle `back`, flat and unblurred, shows from behind `front`, which hides the rest of it
   * when it covers it from side to side and to its foot: what's left is the strip along its top, down to where
   * `front`'s corners have rounded off. Otherwise, all of it shows. Both are taken as turned no further than their
   * planes, and the rect `front` is turned to in `back`'s plane as only what it surely covers.
   */
  cut(back: Readonly<Placement>, front: Readonly<Placement>): number {
    const width = this.size.x / 2;
    const height = this.size.y / 2;
    const radius = this.uniforms.uRadius.value;
    // `front`'s middle, its half size and its corners' radius in `back`'s own pixels.
    const cos = Math.cos(back.roll);
    const sin = Math.sin(back.roll);
    const dx = (front.x - back.x) / back.scale;
    const dy = (front.y - back.y) / back.scale;
    const x = dx * cos + dy * sin;
    const y = dy * cos - dx * sin;
    const size = front.scale / back.scale;
    const askew = front.roll - back.roll;
    const straight = Math.cos(askew);
    const slant = Math.abs(Math.sin(askew));
    const across = size * (width * straight - height * slant);
    const down = size * (height * straight - width * slant);
    // A pixel and a half more each way, for edges' smoothing and the hairline round them.
    const room = 1.5;
    const covered = x - across <= -width - room && x + across >= width + room && y + down >= height + room;
    return covered ? y - down + radius * size + room : WHOLE;
  }

  /** Ends a frame's cards. */
  end(): void {
    this.geometry.instanceCount = this.count;
    for (const attribute of Object.values(this.attributes)) {
      attribute.clearUpdateRanges();
      attribute.addUpdateRange(0, this.count * attribute.itemSize);
      attribute.needsUpdate = true;
    }
  }

  /**
   * Draws every face into one picture, a cell each, in the page's type, as sharp as the screen shows a card. Each
   * face's words are laid out as its sizer's are: the cards are all the same shape, so one layout does for each sizer.
   */
  private async draw(): Promise<void> {
    const faces = this.faces;
    const layouts = new Map(faces.map(({ sizer }) => [sizer, this.layout(sizer)]));
    await Promise.all(
      faces.flatMap(({ card, sizer }) =>
        (layouts.get(sizer) ?? []).map((part) => document.fonts.load(part.font, card[part.words])),
      ),
    );
    const width = this.size.x;
    const height = this.size.y;
    const columns = Math.ceil(Math.sqrt(faces.length));
    const rows = Math.ceil(faces.length / columns);
    const cell = { x: width * (1 + 2 * OVERFLOW[0]), y: height + 2 * width * OVERFLOW[1] };
    const unit = Math.min(
      Math.min(window.devicePixelRatio, 2),
      SHARPEST / width,
      LARGEST / (columns * cell.x),
      LARGEST / (rows * cell.y),
    );
    const canvas = document.createElement("canvas");
    canvas.width = Math.ceil(columns * cell.x * unit);
    canvas.height = Math.ceil(rows * cell.y * unit);
    const context = canvas.getContext("2d");
    if (context == null) return;
    context.textBaseline = "middle";
    faces.forEach(({ card, sizer }, index) => {
      const left = (index % columns) * cell.x + width * OVERFLOW[0];
      const top = Math.floor(index / columns) * cell.y + width * OVERFLOW[1];
      context.setTransform(unit, 0, 0, unit, left * unit, top * unit);
      (layouts.get(sizer) ?? []).forEach((part) => {
        context.font = part.font;
        context.fillStyle = part.colour;
        context.letterSpacing = part.spacing;
        context.textAlign = part.align;
        context.fillText(card[part.words], part.x, part.y);
      });
    });

    const texture = new CanvasTexture(canvas);
    texture.flipY = false;
    texture.premultiplyAlpha = true;
    texture.minFilter = LinearMipmapLinearFilter;
    texture.magFilter = LinearFilter;
    // On the GPU now, rather than the first time the cards come into view.
    this.renderer.initTexture(texture);
    this.texture?.dispose();
    this.texture = texture;
    const { uniforms } = this;
    uniforms.uFaces.value = texture;
    uniforms.uAtlas.value.set(canvas.width, canvas.height);
    uniforms.uCell.value.set(cell.x * unit, cell.y * unit);
    uniforms.uFace.value.set(width * unit, height * unit);
    uniforms.uColumns.value = columns;
    uniforms.uUnit.value = unit;
  }

  /** How `sizer` lays out the words it marks. */
  private layout(sizer: HTMLElement): Part[] {
    return [...sizer.querySelectorAll<HTMLElement>("[data-part]")]
      .filter((part) => part.dataset.part === "deck" || part.dataset.part === "front" || part.dataset.part === "back")
      .map((part) => {
        const style = getComputedStyle(part);
        const centred = style.justifySelf === "center" || style.textAlign === "center";
        return {
          words: part.dataset.part as Part["words"],
          font: `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`,
          colour: style.color,
          spacing: style.letterSpacing === "normal" ? "0px" : style.letterSpacing,
          x: centred ? part.offsetLeft + part.offsetWidth / 2 : part.offsetLeft,
          align: centred ? "center" : "left",
          y: part.offsetTop + part.offsetHeight / 2,
        };
      });
  }
}
