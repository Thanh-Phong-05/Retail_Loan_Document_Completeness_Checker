import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./context/AuthContext";
import { DossierProvider } from "./context/DossierContext";
import DashboardPage from "./pages/DashboardPage";
import LoginPage from "./pages/LoginPage";
import MyDossiersPage from "./pages/MyDossiersPage";
import ReviewWorkspacePage from "./pages/ReviewWorkspacePage";
import SelectPackagePage from "./pages/SelectPackagePage";
import UploadDossierPage from "./pages/UploadDossierPage";

function RoleRoute({ allowedRoles, children }) {
  const { user, isAuthenticated } = useAuth();
  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }
  if (allowedRoles && !allowedRoles.includes(user.role)) {
    return (
      <Navigate
        to={
          user.role === "CUSTOMER"
            ? "/customer/select-package"
            : "/admin/dashboard"
        }
        replace
      />
    );
  }
  return children;
}

function HomeRedirect() {
  const { user, isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return (
    <Navigate
      to={
        user.role === "CUSTOMER"
          ? "/customer/select-package"
          : "/admin/dashboard"
      }
      replace
    />
  );
}

export default function App() {
  return (
    <AuthProvider>
      <DossierProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<HomeRedirect />} />
            <Route path="/login" element={<LoginPage />} />

            {/* Customer Routes */}
            <Route
              path="/customer/select-package"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <SelectPackagePage />
                </RoleRoute>
              }
            />
            <Route
              path="/customer/upload"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <UploadDossierPage />
                </RoleRoute>
              }
            />
            <Route
              path="/customer/my-dossiers"
              element={
                <RoleRoute allowedRoles={["CUSTOMER"]}>
                  <MyDossiersPage />
                </RoleRoute>
              }
            />

            {/* Bank Admin / Officer Routes */}
            <Route
              path="/admin/dashboard"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <DashboardPage queueOnly={false} />
                </RoleRoute>
              }
            />
            <Route
              path="/admin/queue"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <DashboardPage queueOnly />
                </RoleRoute>
              }
            />
            <Route
              path="/admin/upload"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <UploadDossierPage />
                </RoleRoute>
              }
            />
            <Route
              path="/admin/review/:applicationId"
              element={
                <RoleRoute allowedRoles={["OFFICER"]}>
                  <ReviewWorkspacePage />
                </RoleRoute>
              }
            />

            {/* Fallback */}
            <Route path="*" element={<HomeRedirect />} />
          </Routes>
        </BrowserRouter>
      </DossierProvider>
    </AuthProvider>
  );
}
