import { createContext, useContext, useState } from "react";

const STORAGE_KEY = "meridian_mock_auth_user_v1";

export const DEMO_USERS = {
  CUSTOMER: {
    role: "CUSTOMER",
    fullName: "Nguyễn Văn A",
    initials: "VA",
    subtitle: "Khách hàng vay",
    identifier: "079099001234",
    branch: "TP. Hồ Chí Minh",
  },
  OFFICER: {
    role: "OFFICER",
    fullName: "Alex Morgan",
    initials: "AM",
    subtitle: "Loan officer",
    identifier: "alex.morgan@meridianbank.vn",
    branch: "CN TP. Hồ Chí Minh",
  },
};

const AuthContext = createContext(null);

function readStoredUser() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(() => readStoredUser());

  const persistUser = (nextUser) => {
    setUser(nextUser);
    try {
      if (nextUser) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(nextUser));
      } else {
        localStorage.removeItem(STORAGE_KEY);
      }
    } catch {
      // Ignore storage quota errors
    }
  };

  const loginAsCustomer = (identifier = DEMO_USERS.CUSTOMER.identifier) => {
    const next = {
      ...DEMO_USERS.CUSTOMER,
      identifier: identifier?.trim() || DEMO_USERS.CUSTOMER.identifier,
    };
    persistUser(next);
    return next;
  };

  const loginAsOfficer = (identifier = DEMO_USERS.OFFICER.identifier) => {
    const next = {
      ...DEMO_USERS.OFFICER,
      identifier: identifier?.trim() || DEMO_USERS.OFFICER.identifier,
    };
    persistUser(next);
    return next;
  };

  const switchRole = () => {
    if (!user || user.role === "OFFICER") {
      return loginAsCustomer();
    }
    return loginAsOfficer();
  };

  const logout = () => {
    persistUser(null);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        isAuthenticated: Boolean(user),
        loginAsCustomer,
        loginAsOfficer,
        switchRole,
        logout,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used inside AuthProvider");
  }
  return ctx;
}
