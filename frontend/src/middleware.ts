import { NextRequest, NextResponse } from "next/server";

export function middleware(request: NextRequest) {
  if (!request.cookies.get("springkb_session")?.value) {
    const login = new URL("/login", request.url);
    login.searchParams.set("next", request.nextUrl.pathname + request.nextUrl.search);
    return NextResponse.redirect(login);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/search/:path*", "/knowledge/:path*", "/chat/:path*", "/plans/:path*", "/examples/:path*", "/assessment/:path*", "/practice/:path*", "/report/:path*", "/graph/:path*", "/achievements/:path*", "/operations/:path*"],
};
