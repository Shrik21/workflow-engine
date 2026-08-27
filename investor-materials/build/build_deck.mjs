import fs from "node:fs/promises";
import { Presentation, PresentationFile } from "@oai/artifact-tool";

const ROOT = "C:/Users/ASUS/OneDrive/Desktop/New folder/sitepilot/investor-materials";
const ASSETS = `${ROOT}/assets`;
const OUT = `${ROOT}/OrchPilot-Investor-Pitch.pptx`;
const RENDER = `${ROOT}/build/deck-render`;

const C = { ink: "#0B1736", blue: "#276EF1", cyan: "#65C7F7", pale: "#EDF4FF", panel: "#F1F3F6", muted: "#586174", white: "#FFFFFF", green: "#2DA66F", pink: "#D93B7A", rule: "#C8CED9" };
const FONT = "Arial";

async function bytes(path) { const b = await fs.readFile(path); return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength); }
async function writeBlob(path, blob) { await fs.writeFile(path, new Uint8Array(await blob.arrayBuffer())); }

function box(slide, left, top, width, height, fill = C.panel, radius = 0, line = "none") {
  return slide.shapes.add({ geometry: radius ? "roundRect" : "rect", position: { left, top, width, height }, fill,
    line: { style: "solid", fill: line, width: line === "none" ? 0 : 1 }, ...(radius ? { borderRadius: radius } : {}) });
}
function text(slide, value, left, top, width, height, size = 24, color = C.ink, bold = false, align = "left") {
  const s = slide.shapes.add({ geometry: "textbox", position: { left, top, width, height }, fill: "none", line: { style: "solid", fill: "none", width: 0 } });
  s.text = value;
  s.text.style = { fontSize: size, typeface: FONT, color, bold, alignment: align, verticalAlignment: "middle", autoFit: "shrinkText" };
  return s;
}
function title(slide, value, kicker, n) {
  text(slide, kicker.toUpperCase(), 56, 32, 500, 26, 14, C.blue, true);
  text(slide, value, 56, 62, 1168, 68, 38, C.ink, true);
  text(slide, String(n).padStart(2,"0"), 1180, 670, 44, 18, 12, C.muted, false, "right");
}
function notes(slide, lines, sources = []) {
  const src = sources.length ? `\n\n[Sources]\n${sources.map(s => `- ${s}`).join("\n")}` : "";
  slide.speakerNotes.textFrame.setText(`${lines.join("\n")} ${src}`.trim());
  slide.speakerNotes.setVisible(true);
}
async function image(slide, path, alt, position, fit = "contain") {
  return slide.images.add({ blob: await bytes(path), contentType: "image/png", alt, fit, position, geometry: "roundRect", borderRadius: 10 });
}

const p = Presentation.create({ slideSize: { width: 1280, height: 720 } });

// 1 — cover: Codex Grid slide-08 hierarchy (text left, hero right)
{
  const s = p.slides.add(); s.background.fill = C.white;
  box(s, 0, 0, 18, 720, C.blue);
  text(s, "ORCHPILOT", 60, 48, 280, 30, 16, C.blue, true);
  text(s, "The governed orchestration layer for people, systems and AI", 60, 138, 530, 230, 52, C.ink, true);
  text(s, "Durable workflows. Human accountability. Runtime-extensible integrations.", 60, 395, 500, 88, 24, C.muted);
  text(s, "INVESTOR BRIEF • AUGUST 2026", 60, 620, 420, 28, 14, C.muted, true);
  await image(s, `${ASSETS}/03-workflow-designer.png`, "OrchPilot visual workflow designer", { left: 640, top: 70, width: 590, height: 570 }, "cover");
  notes(s, ["Open with the category: governed orchestration, not task automation.", "The product is working locally; the screenshot is from the authenticated application."], ["Local OrchPilot application screenshot, captured 2026-08-26."]);
}

// 2 — problem
{
  const s = p.slides.add(); s.background.fill = C.white; title(s, "Enterprise automation fails at the handoffs", "The problem", 2);
  const items = [
    ["Systems", "Point integrations move data but do not own the end-to-end outcome."],
    ["People", "Approvals, exceptions and accountability are often bolted on after design."],
    ["AI", "Agents add adaptability—but also nondeterminism, cost and governance risk."],
    ["Operations", "Retries, versioning, audit and recovery become custom plumbing in every team."]
  ];
  items.forEach((it,i)=>{ const y=165+i*115; text(s, String(i+1).padStart(2,"0"), 60,y,70,50,28,C.blue,true); text(s,it[0],150,y,190,40,25,C.ink,true); text(s,it[1],350,y,830,64,22,C.muted); });
  notes(s, ["Frame the customer pain as fragmented ownership, not a shortage of automation tools.", "The investor question this sets up: who governs the full journey when people, systems and AI all participate?"], ["Camunda platform positioning: https://camunda.com/platform/", "Gartner BOAT forecast: https://www.gartner.com/en/documents/6530402"]);
}

// 3 — product screenshot
{
  const s = p.slides.add(); s.background.fill = C.white; title(s, "One canvas models the journey; one engine owns the outcome", "The product", 3);
  await image(s, `${ASSETS}/03-workflow-designer.png`, "Workflow designer with AI, flow and human nodes", { left: 55, top: 145, width: 810, height: 515 }, "contain");
  text(s, "Built-in control points", 910, 160, 300, 38, 24, C.ink, true);
  [["AI Agent",C.pink],["Human Form",C.green],["Decision",C.blue],["Immutable publish",C.ink]].forEach((a,i)=>{ box(s,910,225+i*82,275,60,C.pale,8); box(s,925,242+i*82,12,26,a[1],6); text(s,a[0],952,232+i*82,215,38,20,C.ink,true); });
  text(s, "The UI is schema-driven: plugins can publish node definitions without a front-end release.", 910, 565, 280, 72, 18, C.muted);
  notes(s, ["Demonstrate that AI and human tasks are first-class nodes alongside deterministic control flow.", "The schema-driven UI is the bridge from extensibility to product velocity."], ["Local OrchPilot application screenshot, captured 2026-08-26.", "Repository README.md and ARCHITECTURE.md."]);
}

// 4 — architecture
{
  const s = p.slides.add(); s.background.fill = C.white; title(s, "A control plane separates process intent from integration code", "Architecture", 4);
  const xs=[70,345,620,895]; const labels=["Design","Execute","Extend","Govern"]; const bodies=["Visual graph\nVersions + triggers","Durable state\nRetries + compensation","Isolated plugin JARs\nRuntime registration","RBAC + audit\nEncrypted secrets"];
  xs.forEach((x,i)=>{ box(s,x,205,235,245,i===1?C.pale:C.panel,10); text(s,labels[i],x+22,235,190,42,26,C.ink,true); text(s,bodies[i],x+22,300,190,95,21,C.muted); if(i<3){ text(s,"→",x+240,290,35,45,30,C.blue,true,"center"); } });
  box(s,70,510,1060,82,C.ink,8); text(s,"MongoDB persistence • execution checkpoints • event and schedule entry points • REST API",95,525,1010,50,22,C.white,true,"center");
  notes(s, ["The architectural claim is separation: workflows remain stable while integrations evolve as plugins.", "Checkpointed execution and immutable versions support long-running, auditable work."], ["Repository ARCHITECTURE.md, SECURITY.md, WORKFLOW_INSTANCE_LIFECYCLE.md."]);
}

// 5 — evidence metrics using slide-19 hierarchy
{
  const s = p.slides.add(); s.background.fill = C.white; title(s, "This is a platform foundation—not a prototype shell", "What is built", 5);
  text(s, "Architecture-wide review of source-only files (excluding generated targets and dependencies).", 56,130,1100,45,20,C.muted);
  const metrics=[["80K+","Java production lines"],["160","REST handlers"],["13","plugin modules"]];
  metrics.forEach((m,i)=>{const x=56+i*400; box(s,x,235,360,265,C.panel,8); text(s,m[0],x+24,275,310,90,54,C.blue,true); text(s,m[1],x+24,385,310,54,22,C.ink,true);});
  text(s,"Also: 73 core-engine test classes • 28.6K Angular source lines • 27 REST controllers",56,545,1160,55,22,C.muted,true,"center");
  notes(s, ["Use these as engineering-evidence metrics, not traction metrics.", "Avoid implying code volume equals product-market fit; it establishes implementation depth and raises the diligence baseline."], ["Local source inventory from Workflow-OrchPilot/workflow-engine, counted 2026-08-26."]);
}

// 6 — moat
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"The moat compounds at the boundary between reliability and extensibility","Why it can win",6);
  const rows=[
    ["Runtime plugin economy","Validated JARs load in isolated classloaders; new node types appear without engine restart."],
    ["Execution trust","Immutable published versions, checkpoint recovery, deterministic idempotency keys and compensation policies."],
    ["Human accountability","Server-validated forms, assignment rules, history and non-repudiable completion semantics."],
    ["AI with guardrails","Provider abstraction, encrypted credentials, usage visibility and deterministic workflow boundaries."]
  ];
  rows.forEach((r,i)=>{const y=160+i*115; box(s,56,y,1165,88,i%2?C.white:C.panel,4,i%2?C.rule:"none"); text(s,r[0],78,y+18,295,48,23,C.ink,true); text(s,r[1],390,y+12,800,60,20,C.muted);});
  notes(s,["The moat is not the canvas. It is the operational contract across plugins, state, humans and AI.","A future plugin marketplace can turn integration supply into a distribution and revenue loop."],["Repository ARCHITECTURE.md, PLUGIN_OPERATION_FRAMEWORK.md, AI_AGENT_NODE.md, SECURITY.md."]);
}

// 7 — market chart
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"Orchestration spending is consolidating into a strategic control layer","Market timing",7);
  s.charts.add("bar", { position:{left:60,top:175,width:700,height:395}, categories:["2025","2029"], series:[{name:"BOAT spending ($B)",values:[7,21],fill:C.blue}], hasLegend:false, dataLabels:{showValue:true,position:"outEnd"}, yAxis:{majorGridlines:{style:"solid",fill:C.rule,width:1}} });
  text(s,"3×",825,190,300,100,68,C.blue,true);
  text(s,"projected expansion\nin consolidated BOAT spending",825,290,340,88,25,C.ink,true);
  text(s,"Adjacent hyperautomation software is projected to reach $119.2B by 2028.",825,420,340,95,21,C.muted);
  text(s,"Market figures are third-party forecasts—not OrchPilot revenue projections.",825,555,340,42,14,C.muted);
  notes(s,["Lead with the smaller, more directly relevant BOAT market; use hyperautomation only as adjacent context.","The implied 2025–2029 growth is directionally strong; do not overstate addressable share."],["Gartner BOAT forecast: https://www.gartner.com/en/documents/6530402","Gartner hyperautomation market map: https://www.gartner.com/en/documents/6165623"]);
}

// 8 — competitive wedge
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"OrchPilot should own the governed middle—not compete on connector count","Competitive wedge",8);
  const cols=[56,285,480,675,870,1065]; const heads=["Capability","OrchPilot","n8n","Camunda","Temporal","Zapier"];
  heads.forEach((h,i)=>text(s,h,cols[i],160,i===0?215:175,38,17,i===1?C.blue:C.ink,true,"center"));
  const data=[
    ["Visual business workflow","Strong","Strong","Strong","Code-first","Strong"],
    ["Durable execution","Built","Partial","Strong","Category leader","Limited"],
    ["Human tasks + forms","Built","Workflow step","Strong","Custom","Basic"],
    ["Runtime plugin isolation","Differentiator","Node ecosystem","Connectors","Activities","App ecosystem"],
    ["Self-host / control","Built","Available","Available","Available","Cloud-first"]
  ];
  data.forEach((row,r)=>row.forEach((v,c)=>{const y=215+r*76; if(r%2===0)box(s,cols[c],y,c===0?215:175,64,C.panel); text(s,v,cols[c]+6,y+7,(c===0?215:175)-12,50,c===0?17:16,c===1?C.blue:C.muted,c===1,"center");}));
  text(s,"Positioning thesis: more governed than low-code automation; more accessible and extensible than developer-only orchestration.",56,620,1165,42,19,C.ink,true,"center");
  notes(s,["This is a strategic comparison, not a feature-completeness certification.","Do not attack established vendors. Use them to define the white space."],["n8n: https://n8n.io/pricing/","Camunda: https://camunda.com/platform/","Temporal: https://temporal.io/","Zapier partner introduction: https://partnerportal.zapier.com/sys/document/openpdfpreview/Introduction%20to%20Zapier.pdf"]);
}

// 9 — beachhead
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"Start where workflows are long-lived, audited and integration-heavy","Beachhead customers",9);
  const uses=[
    ["Platform engineering","Cloud provisioning, access approvals, environment lifecycle and safe rollback."],
    ["Regulated operations","Customer onboarding, compliance review, exception handling and evidentiary audit."],
    ["IT service delivery","Cross-system incidents, remediation, human escalation and post-action records."]
  ];
  uses.forEach((u,i)=>{const x=56+i*400; text(s,`0${i+1}`,x,180,70,50,30,C.blue,true); text(s,u[0],x,245,340,58,25,C.ink,true); text(s,u[1],x,325,340,130,21,C.muted);});
  box(s,56,525,1165,76,C.ink,6); text(s,"Ideal design partner: 500–5,000 employees • hybrid infrastructure • recurring approval-heavy process • clear compliance owner",80,538,1115,48,19,C.white,true,"center");
  notes(s,["Focus avoids the connector-count arms race and selects processes where governance and recovery are budget-worthy.","Design partners should bring one painful process, an executive owner and a measurable baseline."],["Temporal platform-engineering use cases: https://temporal.io/solutions/platform-engineering","Camunda use cases: https://camunda.com/platform/use-cases/"]);
}

// 10 — business model
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"A hybrid platform model can align price with operational value","Proposed business model",10);
  const layers=[
    ["Platform subscription","Annual fee for environments, governance, collaboration, support and deployment choice."],
    ["Execution bands","Usage grows with completed workflow value—not seats or number of design drafts."],
    ["Private plugin marketplace","Paid certified integrations, partner revenue share and enterprise connector packs."],
    ["Implementation partners","Fixed-scope launch packages; services taper as partner capacity compounds."]
  ];
  layers.forEach((l,i)=>{const y=160+i*112; text(s,`${i+1}`,65,y,55,55,30,C.blue,true,"center"); text(s,l[0],145,y,330,52,24,C.ink,true); text(s,l[1],485,y,700,66,20,C.muted);});
  text(s,"Recommendation—not current pricing. Validate willingness-to-pay with five design partners before fixing tiers.",56,625,1160,34,16,C.muted,false,"center");
  notes(s,["Pricing is intentionally presented as a hypothesis.","Execution-based value metrics are legible, but large customers will still demand predictable commitments and overage protection."],["n8n pricing model context: https://n8n.io/pricing/"]);
}

// 11 — roadmap timeline based on slide-17 hierarchy
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"Eighteen months turns strong engineering into an investable company","De-risking plan",11);
  box(s,80,340,1060,3,C.ink); const milestones=[
    [100,"0–3 months","Prove wedge","5 design partners\n1 repeatable process"],
    [440,"4–9 months","Enterprise readiness","SSO/SCIM • HA\nobservability • policy"],
    [780,"10–18 months","Scale distribution","3 paid pilots\npartner/plugin program"]
  ];
  milestones.forEach(m=>{box(s,m[0],329,24,24,C.blue,12); text(s,m[1],m[0]-5,275,230,35,17,C.blue,true); text(s,m[2],m[0]-5,390,290,44,24,C.ink,true); text(s,m[3],m[0]-5,445,290,80,20,C.muted);});
  text(s,"North-star validation: time-to-first-production workflow, successful execution rate, human wait time, and expansion to a second process.",80,585,1060,48,19,C.ink,true,"center");
  notes(s,["Tie spend to de-risking, not feature volume.","The second production process is the early signal that OrchPilot is becoming a platform rather than a project."],["Roadmap is a recommendation derived from repository assessment and competitor expectations."]);
}

// 12 — risks
{
  const s=p.slides.add(); s.background.fill=C.white; title(s,"The biggest risk is breadth before proof—not technical feasibility","Risks and answers",12);
  const risks=[
    ["Crowded category","Anchor on governed, approval-heavy orchestration; avoid generic automation messaging."],
    ["Enterprise trust gap","Prioritize SSO/SCIM, HA benchmarks, deployment automation, security review and support SLAs."],
    ["Connector cold start","Ship certified packs for one vertical and expose a stable SDK/marketplace path."],
    ["Founder-market evidence","Run structured discovery; publish quantified before/after outcomes from pilots."]
  ];
  risks.forEach((r,i)=>{const y=160+i*112; box(s,56,y,300,78,i===0?C.pale:C.panel,6); text(s,r[0],72,y+12,268,50,21,C.ink,true); text(s,r[1],390,y+5,800,70,20,C.muted);});
  notes(s,["Investors will find these gaps. Naming them builds credibility and shows capital discipline.","Do not claim enterprise readiness solely from feature presence; prove operational scale and procurement readiness."],["Repository assessment and current competitor positioning."]);
}

// 13 — ask / close
{
  const s=p.slides.add(); s.background.fill=C.ink;
  text(s,"ORCHPILOT",60,45,250,28,15,C.cyan,true);
  text(s,"Make every critical workflow recoverable, governable and extensible.",60,150,1080,150,50,C.white,true);
  text(s,"Seed objective",60,365,220,35,20,C.cyan,true);
  text(s,"Convert a working orchestration platform into repeatable enterprise adoption.",60,410,830,75,28,C.white,true);
  text(s,"Use of funds: enterprise readiness • 5 design partners • 3 paid pilots • partner-led integration supply",60,525,1080,70,21,"#C9D5EE");
  text(s,"Funding amount and valuation: INSERT AFTER FOUNDER DECISION",60,635,650,22,13,"#8EA0C4",true);
  notes(s,["Close on the mission and the next proof points.","Insert the actual raise amount, runway, founder story and current traction before external distribution."],["Milestones are proposed; no funding amount or traction was provided."]);
}

await fs.mkdir(RENDER,{recursive:true});
for (const [i,s] of p.slides.items.entries()) {
  await writeBlob(`${RENDER}/slide-${String(i+1).padStart(2,"0")}.png`, await p.export({slide:s,format:"png",scale:1}));
  await fs.writeFile(`${RENDER}/slide-${String(i+1).padStart(2,"0")}.layout.json`, await (await s.export({format:"layout"})).text());
}
await writeBlob(`${RENDER}/montage.webp`, await p.export({format:"webp",montage:true,scale:1}));
const pptx=await PresentationFile.exportPptx(p);
await pptx.save(OUT);
console.log(OUT);
