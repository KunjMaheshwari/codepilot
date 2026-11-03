export default function RepositoriesPage() {
  return (
    <div className="flex flex-col gap-4 pt-6">
      <h1 className="text-3xl font-bold tracking-tight">Repositories</h1>
      <div className="flex h-[400px] items-center justify-center rounded-md border border-dashed p-8 text-center animate-in fade-in-50">
        <div className="mx-auto flex max-w-[420px] flex-col items-center justify-center text-center">
          <h2 className="mt-6 text-xl font-semibold">No repositories added</h2>
          <p className="mb-8 mt-2 text-center text-sm font-normal leading-6 text-muted-foreground">
            You have not added any repositories yet. Connect a repository to start reviewing code.
          </p>
        </div>
      </div>
    </div>
  );
}
