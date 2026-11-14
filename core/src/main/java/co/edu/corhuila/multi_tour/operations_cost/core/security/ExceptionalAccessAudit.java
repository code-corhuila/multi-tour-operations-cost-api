package co.edu.corhuila.multi_tour.operations_cost.core.security;

/** Port: reports each use of the token of an exceptional or emergency access. It has no parameter for data on purpose (spec 012). */
public interface ExceptionalAccessAudit {

  void used(Caller who, String method, String route, String area);
}
