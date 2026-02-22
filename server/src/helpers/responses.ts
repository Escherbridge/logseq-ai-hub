export function successResponse<T>(data: T, status = 200): Response {
  return Response.json({ success: true, data }, { status });
}

export function errorResponse(status: number, error: string): Response {
  return Response.json({ success: false, error }, { status });
}

export function notFoundResponse(message = "Not found"): Response {
  return errorResponse(404, message);
}
