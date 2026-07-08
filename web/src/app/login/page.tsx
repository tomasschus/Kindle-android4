"use client";

import { useState, FormEvent } from "react";
import { useRouter } from "next/navigation";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await fetch(`/api/auth/${mode === "login" ? "login" : "register"}`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setError(errorMessage(body.error, mode));
        return;
      }
      router.push("/dashboard");
      router.refresh();
    } catch {
      setError("No se pudo conectar con el servidor.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex flex-1 items-center justify-center bg-zinc-50 px-4 dark:bg-black">
      <div className="w-full max-w-sm rounded-2xl border border-zinc-200 bg-white p-8 shadow-sm dark:border-zinc-800 dark:bg-zinc-950">
        <h1 className="text-2xl font-semibold text-zinc-900 dark:text-zinc-50">Kindle Casero</h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          {mode === "login" ? "Inicia sesión para gestionar tus PDFs." : "Crea tu cuenta."}
        </p>

        <form onSubmit={onSubmit} className="mt-6 flex flex-col gap-4">
          <label className="flex flex-col gap-1 text-sm text-zinc-700 dark:text-zinc-300">
            Usuario
            <input
              className="rounded-lg border border-zinc-300 px-3 py-2 text-base outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
            />
          </label>
          <label className="flex flex-col gap-1 text-sm text-zinc-700 dark:text-zinc-300">
            Contraseña
            <input
              type="password"
              className="rounded-lg border border-zinc-300 px-3 py-2 text-base outline-none focus:border-zinc-500 dark:border-zinc-700 dark:bg-zinc-900"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === "login" ? "current-password" : "new-password"}
              required
              minLength={mode === "register" ? 8 : undefined}
            />
          </label>

          {error && <p className="text-sm text-red-600 dark:text-red-400">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="mt-2 rounded-lg bg-zinc-900 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:opacity-50 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {loading ? "Cargando…" : mode === "login" ? "Entrar" : "Crear cuenta"}
          </button>
        </form>

        <button
          className="mt-4 text-sm text-zinc-500 underline-offset-2 hover:underline dark:text-zinc-400"
          onClick={() => {
            setMode(mode === "login" ? "register" : "login");
            setError(null);
          }}
        >
          {mode === "login" ? "¿Primera vez? Crear cuenta" : "Ya tengo cuenta"}
        </button>
      </div>
    </div>
  );
}

function errorMessage(code: string | undefined, mode: "login" | "register"): string {
  if (mode === "login") {
    if (code === "invalid_credentials") return "Usuario o contraseña incorrectos.";
    return "No se pudo iniciar sesión.";
  }
  if (code === "signup_disabled") return "El registro está deshabilitado en este servidor.";
  if (code === "username_taken") return "Ese usuario ya existe.";
  if (code === "invalid_body") return "Usuario o contraseña inválidos (mínimo 8 caracteres).";
  return "No se pudo crear la cuenta.";
}
