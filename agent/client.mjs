import { readFile } from "node:fs/promises";

/** No automatic retries: query status after an unknown transport outcome. */
export class PerimeterAgent {
  constructor(url, token) {
    const endpoint = new URL(url);
    if (endpoint.protocol !== "http:" || endpoint.hostname !== "127.0.0.1") throw new Error("Only loopback HTTP is supported");
    this.url = endpoint.origin; this.token = token;
  }
  static async connect(connectionFile) {
    const {url, token} = JSON.parse(await readFile(connectionFile, "utf8"));
    return new PerimeterAgent(url, token);
  }
  async request(method, path, body) {
    const response = await fetch(this.url + path, {method,
      headers: {Authorization: "Bearer " + this.token, "Content-Type": "application/json"},
      body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000)});
    const result = await response.json();
    if (!response.ok) throw Object.assign(new Error(result.error || "API request failed"), {status: response.status, result});
    return result;
  }
  commands() { return this.request("GET", "/v1/commands"); }
  command(command) { return this.request("POST", "/v1/commands", {command}); }
  status() { return this.request("GET", "/v1/status"); }
  world() { return this.request("GET", "/v1/world"); }
  chunks(radius) { return this.request("GET", radius === undefined ? "/v1/chunks" : `/v1/chunks?radius=${encodeURIComponent(radius)}`); }
  chunk(cx, cz) { return this.request("GET", `/v1/chunk?cx=${encodeURIComponent(cx)}&cz=${encodeURIComponent(cz)}`); }
  entities(itemsOnly = false) { return this.request("GET", itemsOnly ? "/v1/entities?items=true" : "/v1/entities"); }
  plan(task) { return this.request("POST", "/v1/tasks/plan", task); }
  start(task) { return this.request("POST", "/v1/tasks", task); }
  pause(taskId) { return this.request("POST", "/v1/tasks/pause", {taskId}); }
  resume(taskId) { return this.request("POST", "/v1/tasks/resume", {taskId}); }
  stop(taskId) { return this.request("POST", "/v1/tasks/stop", {taskId}); }
}
