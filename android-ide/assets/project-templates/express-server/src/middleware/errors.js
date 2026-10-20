export function errorHandler(error, _request, response, _next) {
  response.status(400).json({ error: error.message || "Request failed" });
}
