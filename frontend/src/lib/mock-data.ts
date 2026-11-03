export const mockDashboardStats = {
  totalReviews: 1284,
  issuesDetected: 342,
  criticalIssues: 45,
  activeRepositories: 12,
};

export const mockActivityData = [
  { name: "Mon", reviews: 4 },
  { name: "Tue", reviews: 7 },
  { name: "Wed", reviews: 5 },
  { name: "Thu", reviews: 11 },
  { name: "Fri", reviews: 8 },
  { name: "Sat", reviews: 2 },
  { name: "Sun", reviews: 3 },
];

export const mockRecentReviews = [
  {
    id: "REV-1234",
    repository: "acme-corp/frontend",
    prNumber: 42,
    status: "completed",
    issues: 3,
    time: "2 hours ago",
  },
  {
    id: "REV-1235",
    repository: "acme-corp/backend",
    prNumber: 89,
    status: "processing",
    issues: 0,
    time: "45 mins ago",
  },
  {
    id: "REV-1236",
    repository: "personal/portfolio",
    prNumber: 12,
    status: "completed",
    issues: 0,
    time: "5 hours ago",
  },
  {
    id: "REV-1237",
    repository: "acme-corp/auth-service",
    prNumber: 21,
    status: "failed",
    issues: 0,
    time: "1 day ago",
  },
];
